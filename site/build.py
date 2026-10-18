#!/usr/bin/env python3
"""Build the documentation site in site/pages into a static folder.

Pages are Markdown with a small front matter block. Links between pages are
written as relative .md links, so the sources also read correctly on GitHub;
the build rewrites them to the site's URLs. Every output link is relative, so
the site works under /pinyon-shift/ on GitHub Pages or at a domain root.

    python site/build.py [--out site/_build]
"""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import shutil
import sys
import tomllib
import urllib.request
from dataclasses import dataclass
from pathlib import Path

import markdown
from markdown.extensions.toc import slugify

SITE = Path(__file__).resolve().parent
ROOT = SITE.parent
REPO = "arcanite24/pinyon-shift"
REPO_URL = f"https://github.com/{REPO}"
# The site documents the development branch, ahead of the latest release.
BRANCH = "dev"
SECTIONS = ["Start", "Playing", "Technical", "Internals", "Reverse engineering", "Project"]

# Files copied from the repository into the site, by output name.
STATIC_FROM_REPO = {
    "img/logo.png": "launcher/PinyonShift.Launcher/Branding/logo-light.png",
    "img/launcher-ready.png": ".github/launcher-ready.png",
    "img/launcher-settings.png": ".github/launcher-settings.png",
    "favicon.ico": "launcher/PinyonShift.Launcher/Branding/pinyon-shift.ico",
}

# A paragraph of its own, {{diagram:name|Caption}}, inlines site/diagrams/name.svg.
DIAGRAM = re.compile(r"<p>\{\{diagram:([\w-]+)\|([^}]*)\}\}</p>")
# A paragraph of its own, {{findings:topic}} or {{findings:topic/group}}, renders
# the reverse-engineering records in site/findings/topic.toml.
FINDINGS = re.compile(r"<p>\{\{findings:([\w-]+)(?:/([^}]+))?\}\}</p>")
# {{addresses:all}}, {{addresses:symbols}}, {{addresses:hooks}}, {{addresses:offsets}}.
ADDRESSES = re.compile(r"<p>\{\{addresses:(\w+)\}\}</p>")
CONFIDENCE = {"confirmed": "Confirmed", "probable": "Probable", "guess": "Guess"}
FINDING_KEYS = {"id", "group", "base", "v4", "name", "what", "how", "confidence", "note",
                "negative", "issue", "source"}
# Guest addresses inside a finding's base and v4 fields: sub_82D505C0, 0x8207AA58,
# or a bare 825A6320 (thread entries and code sites).
GUEST_ADDRESS = re.compile(r"\b(?:sub_|0x)?(8[23][0-9A-Fa-f]{6})\b")
MD_LINK = re.compile(r'href="(?!https?:|mailto:|#)([^"#]+?)\.md(#[^"]*)?"')


@dataclass
class Page:
    source: Path
    slug: str  # "" for the home page
    title: str
    section: str
    order: int
    description: str
    body: str

    @property
    def depth(self) -> int:
        return 0 if not self.slug else 1

    @property
    def output(self) -> Path:
        return Path(self.slug, "index.html") if self.slug else Path("index.html")


def read_page(path: Path) -> Page:
    text = path.read_text(encoding="utf-8")
    meta: dict[str, str] = {}
    if text.startswith("---\n"):
        header, text = text[4:].split("\n---\n", 1)
        for line in header.splitlines():
            key, _, value = line.partition(":")
            meta[key.strip()] = value.strip()
    slug = "" if path.stem == "index" else path.stem
    section = meta.get("section", "Start")
    if section not in SECTIONS:
        raise SystemExit(f"{path.name}: unknown section {section!r}")
    return Page(path, slug, meta["title"], section, int(meta.get("order", "99")),
                meta.get("description", ""), text)


def latest_release() -> dict | None:
    """The latest release's tag, date and launcher asset, or None offline."""
    request = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases/latest",
                                     headers={"Accept": "application/vnd.github+json"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            data = json.load(response)
    except (OSError, ValueError) as error:
        print(f"warning: latest release unavailable ({error}); building without it", file=sys.stderr)
        return None
    asset = next((a for a in data.get("assets", []) if a["name"].endswith(".zip")), None)
    return {
        "tag": data["tag_name"],
        "date": data["published_at"][:10],
        "url": data["html_url"],
        "asset": asset and {"name": asset["name"], "url": asset["browser_download_url"],
                            "size": f"{asset['size'] / 1e6:.0f} MB"},
    }


def release_block(release: dict | None) -> str:
    latest = f"{REPO_URL}/releases/latest"
    if not release or not release["asset"]:
        return (f'<div class="release"><div class="release-main"><p class="release-label">Latest release</p>'
                f'<a class="button" href="{latest}">Download from GitHub Releases</a></div></div>')
    asset = release["asset"]
    return f"""<div class="release">
  <div class="release-main">
    <p class="release-label">Latest release</p>
    <p class="release-version">{html.escape(release['tag'])} <span>{release['date']}</span></p>
  </div>
  <div class="release-actions">
    <a class="button" href="{asset['url']}">{html.escape(asset['name'])} <span>{asset['size']}</span></a>
    <a class="quiet-link" href="{release['url']}">Release notes</a>
    <a class="quiet-link" href="{REPO_URL}/releases">All releases</a>
  </div>
</div>"""


def channel_note(release: dict | None) -> str:
    newer = f"newer than {html.escape(release['tag'])}" if release else "newer than the latest release"
    return (f'<p class="channel-note">These docs follow the <code>{BRANCH}</code> branch and can '
            f'describe changes {newer}. The <a href="{REPO_URL}/releases/latest">release notes</a> '
            f'list what the download contains.</p>')


CELL_STATES = {"yes": "Yes", "partly": "Partly", "not yet": "Not yet", "n/a": "—"}


def feature_cell(cell: str) -> str:
    """One platform cell: its state, then the note after the colon."""
    state, _, note = cell.partition(":")
    state = state.strip()
    if state not in CELL_STATES:
        raise SystemExit(f"features.toml: unknown state {state!r} in {cell!r}")
    note = note.strip().replace("|", r"\|")
    label = CELL_STATES[state]
    css = state.replace(" ", "-").replace("/", "")
    return f'<span class="state state-{css}">{label}</span>' + (f" {note}" if note else "")


def features_markdown() -> str:
    """The Features page's two tables, from site/features.toml (#455)."""
    data = tomllib.loads((SITE / "features.toml").read_text(encoding="utf-8"))
    platforms = data["platforms"]
    lines = ["## Compared with the original", "",
             "| Feature | Xbox 360 | Pinyon Shift |", "| --- | --- | --- |"]
    for row in data["original"]:
        lines.append(f"| [{row['feature']}]({row['link']}) | {row['xbox']} | {row['pinyon']} |")
    lines += ["", "## By platform", "",
              "| Feature | " + " | ".join(platforms) + " |",
              "| --- |" + " --- |" * len(platforms)]
    for row in data["platform"]:
        if len(row["cells"]) != len(platforms):
            raise SystemExit(f"features.toml: {row['feature']} needs {len(platforms)} cells")
        lines.append(f"| [{row['feature']}]({row['link']}) | " +
                     " | ".join(feature_cell(c) for c in row["cells"]) + " |")
    return "\n".join(lines) + "\n"


@dataclass
class Finding:
    topic: str
    id: str
    group: str
    base: str
    v4: str
    name: str
    what: str
    how: str
    confidence: str
    note: str
    negative: bool
    issue: str
    source: str
    page: str = ""  # the page slug that shows it, set while building


def load_findings() -> dict[str, list[Finding]]:
    """Every record in site/findings/<topic>.toml, checked."""
    topics: dict[str, list[Finding]] = {}
    for path in sorted((SITE / "findings").glob("*.toml")):
        data = tomllib.loads(path.read_text(encoding="utf-8"))
        seen: set[str] = set()
        records = []
        for number, raw in enumerate(data.get("finding", []), 1):
            where = f"{path.name} finding {number}"
            unknown = set(raw) - FINDING_KEYS
            if unknown:
                raise SystemExit(f"{where}: unknown keys {sorted(unknown)}")
            if not raw.get("name") or not raw.get("what"):
                raise SystemExit(f"{where}: needs a name and a what")
            confidence = raw.get("confidence", "")
            if confidence not in CONFIDENCE:
                raise SystemExit(f"{where}: confidence must be one of {list(CONFIDENCE)}")
            ident = raw.get("id") or slugify(raw["name"], "-")
            if ident in seen:
                raise SystemExit(f"{where}: duplicate id {ident!r}; give it an explicit id")
            seen.add(ident)
            records.append(Finding(path.stem, ident, raw.get("group", ""), raw.get("base", ""),
                                   raw.get("v4", ""), raw["name"], raw["what"].strip(),
                                   raw.get("how", "").strip(), confidence, raw.get("note", ""),
                                   bool(raw.get("negative", False)), raw.get("issue", ""),
                                   raw.get("source", "")))
        topics[path.stem] = records
    return topics


def inline_markdown(text: str) -> str:
    rendered = markdown.markdown(text, extensions=["attr_list"])
    return re.sub(r"^<p>(.*)</p>$", r"\1", rendered.strip(), flags=re.S)


def issue_links(text: str) -> str:
    return re.sub(r"#(\d+)", lambda m: f'<a href="{REPO_URL}/issues/{m.group(1)}">#{m.group(1)}</a>',
                  html.escape(text))


def source_link(source: str) -> str:
    """'docs/X.md item; src/y.cpp Name' -> links to the files on GitHub, the rest as text.

    A path may name a commit (docs/X.md@1a2b3c4d5e6f) for a file that has
    since changed, so the link keeps pointing at the text the record cites.
    """
    parts = []
    for part in (p.strip() for p in source.split(";")):
        path, _, rest = part.partition(" ")
        path, _, commit = path.partition("@")
        tail = f" {html.escape(rest)}" if rest else ""
        if (re.fullmatch(r"[\w./-]+\.(md|toml|py|cpp|h|inc|json)", path)
                and (re.fullmatch(r"[0-9a-f]{7,40}", commit) or (not commit and (ROOT / path).exists()))):
            parts.append(f'<a href="{REPO_URL}/blob/{commit or BRANCH}/{path}">{html.escape(path)}</a>{tail}')
        else:
            parts.append(html.escape(part))
    return "; ".join(p for p in parts if p)


def address_cell(text: str) -> str:
    parts = [p.strip() for p in re.split(r",\s*(?![^()]*\))", text) if p.strip()]
    return "<br>".join(f"<code>{html.escape(p)}</code>" for p in parts) if parts else "—"


def findings_html(records: list[Finding]) -> str:
    rows = []
    for f in records:
        confidence = f'<span class="conf conf-{f.confidence}">{CONFIDENCE[f.confidence]}</span>'
        if f.negative:
            confidence += ' <span class="conf conf-negative">Negative</span>'
        if f.note:
            confidence += f'<span class="conf-note">{inline_markdown(f.note)}</span>'
        details = [f"<strong>Established by</strong> {inline_markdown(f.how)}" if f.how else ""]
        if f.source:
            details.append(f"<strong>Detail</strong> {source_link(f.source)}")
        if f.issue:
            details.append(f"<strong>Issue</strong> {issue_links(f.issue)}")
        details = [d for d in details if d]
        v4 = f'<span class="v4-label">v4</span>{address_cell(f.v4)}' if f.v4 else ""
        rows.append(
            f'<tr id="{f.topic}-{f.id}" data-confidence="{f.confidence}">'
            f'<td class="addr">{address_cell(f.base)}{v4}</td>'
            f'<td><a class="finding-name" href="#{f.topic}-{f.id}">{html.escape(f.name)}</a> '
            f'{inline_markdown(f.what)}'
            + (f'<p class="how">{"<br>".join(details)}</p>' if details else "") +
            f'</td><td class="confidence">{confidence}</td></tr>')
    return table_html(["Address", "Finding", "Confidence"], rows)


def findings_for(topics: dict[str, list[Finding]], topic: str, group: str | None,
                 page: Page) -> str:
    if topic not in topics:
        raise SystemExit(f"{page.source.name}: no site/findings/{topic}.toml")
    records = [f for f in topics[topic] if group is None or f.group == group]
    if not records:
        raise SystemExit(f"{page.source.name}: no findings in {topic}/{group}")
    for f in records:
        if f.page and f.page != page.slug:
            raise SystemExit(f"{topic}/{f.id} is shown on both {f.page} and {page.slug}")
        f.page = page.slug
    return findings_html(records)


def hook_comments() -> dict[str, str]:
    """The comment above each [[midasm_hook]] in the main-XEX analysis file."""
    notes: dict[str, str] = {}
    pending: list[str] = []
    current = ""
    for line in (ROOT / "config/rexglue/analysis/main-xex.toml").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("#"):
            pending.append(line.lstrip("# "))
        elif line == "[[midasm_hook]]":
            current, pending = " ".join(pending), []
        elif line.startswith("name = "):
            name = line.split("=", 1)[1].strip().strip('"')
            notes[f"hook.{name}"] = current
        elif line:
            pending = []
    return notes


def symbol_table() -> tuple[list[dict], list[dict]]:
    """Mod symbols, hooks and offsets with base and v4 values and their comments."""
    def entries(path: str, kind: str) -> dict[str, int]:
        pattern = re.compile(rf'PINYON_{kind}\("([^"]+)", (0x[0-9A-Fa-f]+|\d+)\)')
        return {m.group(1): int(m.group(2), 0)
                for m in pattern.finditer((ROOT / path).read_text(encoding="utf-8"))}

    comments = hook_comments()
    for line in (ROOT / "config/mod/fh1-symbols.toml").read_text(encoding="utf-8").splitlines():
        match = re.match(r'"([^"]+)"\s*=\s*\S+\s*#\s*(.+)', line)
        if match:
            comments[match.group(1)] = match.group(2).strip()
    base, v4 = "src/mod/fh1_symbols.inc", "src/mod/fh1_symbols_v4.inc"
    v4_symbols, v4_offsets = entries(v4, "SYMBOL"), entries(v4, "OFFSET")
    symbols = [{"name": n, "base": a, "v4": v4_symbols.get(n),
                "kind": "hook" if n.startswith("hook.") else "symbol",
                "description": comments.get(n, "")}
               for n, a in entries(base, "SYMBOL").items()]
    offsets = [{"name": n, "base": o, "v4": v4_offsets.get(n), "description": comments.get(n, "")}
               for n, o in sorted(entries(base, "OFFSET").items())]
    return sorted(symbols, key=lambda s: s["base"]), offsets


def hex_cell(value: int | None) -> str:
    return f'<td class="addr"><code>{value:08X}</code></td>' if value is not None else "<td>—</td>"


def table_html(headers: list[str], rows: list[str], cls: str = "findings") -> str:
    head = "".join(f"<th>{h}</th>" for h in headers)
    return f'<table class="{cls}"><thead><tr>{head}</tr></thead><tbody>{"".join(rows)}</tbody></table>'


def address_index(topics: dict[str, list[Finding]], prefix: str) -> tuple[dict[str, str], dict]:
    """The address index tables, and the same data for symbols.json."""
    rows: list[dict] = []
    for records in topics.values():
        for f in records:
            if not f.page:
                raise SystemExit(f"finding {f.topic}/{f.id} is not shown on any page")
            for match in GUEST_ADDRESS.finditer(f.base):
                rows.append({"address": int(match.group(1), 16), "name": f.name, "kind": f.topic,
                             "v4": f.v4, "confidence": f.confidence,
                             "page": f"{f.page}/#{f.topic}-{f.id}"})
    symbols, offsets = symbol_table()
    anchors = {"symbol": "mod-symbols", "hook": "hook-sites"}
    for s in symbols:
        rows.append({"address": s["base"], "name": s["name"],
                     "kind": "mod symbol" if s["kind"] == "symbol" else "hook",
                     "v4": f"{s['v4']:08X}" if s["v4"] else "", "confidence": "confirmed",
                     "page": f"game-addresses/#{anchors[s['kind']]}"})
    rows.sort(key=lambda r: (r["address"], r["kind"], r["name"]))

    index = [f'<tr data-confidence="{r["confidence"]}">{hex_cell(r["address"])}'
             f'<td><a href="{prefix}{r["page"]}">{html.escape(r["name"])}</a></td>'
             f'<td>{html.escape(r["kind"])}</td><td class="addr">{html.escape(r["v4"]) or "—"}</td></tr>'
             for r in rows]
    def symbol_rows(kind: str) -> list[str]:
        return [f'<tr><td><code>{html.escape(s["name"].removeprefix("hook."))}</code></td>'
                f'{hex_cell(s["base"])}{hex_cell(s["v4"])}<td>{html.escape(s["description"])}</td></tr>'
                for s in symbols if s["kind"] == kind]
    offset_rows = [f'<tr><td><code>{html.escape(o["name"])}</code></td><td align="right">{o["base"]}</td>'
                   f'<td align="right">{"—" if o["v4"] is None else o["v4"]}</td>'
                   f'<td>{html.escape(o["description"])}</td></tr>' for o in offsets]
    named = ["Name", "Base", "v4", "What it is"]
    parts = {
        "all": table_html(["Base", "Name", "Kind", "v4"], index, "findings address-index"),
        "symbols": table_html(named, symbol_rows("symbol")),
        "hooks": table_html(named, symbol_rows("hook")),
        "offsets": table_html(["Name", '<span class="num">Base</span>', '<span class="num">v4</span>',
                               "What it is"], offset_rows),
    }
    export = {
        "executables": {"base": "default.xex", "v4": "default.xex+default.xexp"},
        "addresses": [r | {"address": f"0x{r['address']:08X}"} for r in rows],
        "offsets": offsets,
    }
    return parts, export


def render_markdown(text: str) -> tuple[str, list[dict]]:
    converter = markdown.Markdown(
        extensions=["tables", "fenced_code", "codehilite", "toc", "attr_list", "md_in_html"],
        extension_configs={
            "codehilite": {"guess_lang": False, "css_class": "code"},
            "toc": {"toc_depth": "2-3", "slugify": slugify, "permalink": "#",
                    "permalink_class": "anchor", "permalink_title": "Link to this section"},
        },
    )
    body = converter.convert(text)
    return body, converter.toc_tokens


def diagram_html(name: str, caption: str) -> str:
    """An inline SVG diagram, themed by the site's colours through CSS classes."""
    path = SITE / "diagrams" / f"{name}.svg"
    if not path.is_file():
        raise SystemExit(f"missing diagram {path.relative_to(ROOT)}")
    svg = path.read_text(encoding="utf-8").strip()
    # Marker ids are page-global once inlined; keep each diagram's own.
    svg = svg.replace('id="arrow"', f'id="arrow-{name}"').replace("url(#arrow)", f"url(#arrow-{name})")
    return f'<figure class="diagram">{svg}<figcaption>{caption}</figcaption></figure>'


def flatten_toc(tokens: list[dict]) -> list[dict]:
    items = []
    for token in tokens:
        if token["level"] == 1:
            items += flatten_toc(token["children"])
            continue
        items.append(token)
        items += [child for child in token["children"] if child["level"] == 3]
    return items


def rewrite_links(body: str, page: Page, slugs: set[str]) -> str:
    prefix = "../" * page.depth

    def replace(match: re.Match) -> str:
        target, anchor = match.group(1), match.group(2) or ""
        name = Path(target).name
        stem = "" if name == "index" else name
        if "/" in target or stem and stem not in slugs:
            raise SystemExit(f"{page.source.name}: link to {target}.md is not a site page; "
                             f"use a full {REPO_URL} URL")
        return f'href="{prefix}{stem + "/" if stem else ""}{anchor}"' if stem or anchor \
            else f'href="{prefix or "./"}"'

    return MD_LINK.sub(replace, body)


def page_href(prefix: str, page: Page) -> str:
    return prefix + (page.slug + "/" if page.slug else "") or "./"


def nav_html(pages: list[Page], current: Page | None, prefix: str) -> str:
    parts = []
    for section in SECTIONS:
        entries = [p for p in pages if p.section == section]
        if not entries:
            continue
        parts.append(f'<p class="nav-section">{section}</p><ul>')
        for page in entries:
            attrs = ' aria-current="page"' if page is current else ""
            parts.append(f'<li><a href="{page_href(prefix, page)}"{attrs}>{html.escape(page.title)}</a></li>')
        if section == "Project":
            parts.append(f'<li><a href="{REPO_URL}/blob/{BRANCH}/docs/ROADMAP.md">Roadmap '
                         f'<span class="ext" aria-hidden="true">↗</span></a></li>')
            parts.append(f'<li><a href="{REPO_URL}/blob/{BRANCH}/CHANGELOG.md">Changelog '
                         f'<span class="ext" aria-hidden="true">↗</span></a></li>')
        parts.append("</ul>")
    return "\n".join(parts)


def toc_html(items: list[dict]) -> str:
    if len(items) < 2:
        return ""
    links = "".join(
        f'<li class="toc-l{item["level"]}"><a href="#{item["id"]}">{item["name"]}</a></li>'
        for item in items)
    return f'<nav class="toc" aria-label="On this page"><p>On this page</p><ul>{links}</ul></nav>'


def pager_html(pages: list[Page], current: Page) -> str:
    index = pages.index(current)
    prefix = "../" * current.depth
    cells = []
    for offset, label, cls in ((-1, "Previous", "prev"), (1, "Next", "next")):
        position = index + offset
        if 0 <= position < len(pages):
            page = pages[position]
            cells.append(f'<a class="pager-{cls}" href="{page_href(prefix, page)}"><span>{label}</span>'
                         f'{html.escape(page.title)}</a>')
        else:
            cells.append("<span></span>")
    return f'<nav class="pager" aria-label="Pages">{"".join(cells)}</nav>'


def build(out: Path) -> None:
    pages = sorted((read_page(p) for p in (SITE / "pages").glob("*.md")),
                   key=lambda p: (SECTIONS.index(p.section), p.order, p.title))
    slugs = {p.slug for p in pages if p.slug}
    template = (SITE / "template.html").read_text(encoding="utf-8")
    release = latest_release()
    topics = load_findings()

    if out.exists():
        shutil.rmtree(out)
    (out / "img").mkdir(parents=True)
    for name in ("style.css", "site.js"):
        shutil.copyfile(SITE / "static" / name, out / name)
    for name, source in STATIC_FROM_REPO.items():
        shutil.copyfile(ROOT / source, out / name)

    rendered_pages: list[tuple[Page, str, list[dict]]] = []
    for page in pages:
        text = page.body.replace("{{release}}", release_block(release))
        if "{{features}}" in text:
            text = text.replace("{{features}}", features_markdown())
        body, toc = render_markdown(text)
        body = FINDINGS.sub(lambda m: findings_for(topics, m.group(1), m.group(2), page), body)
        rendered_pages.append((page, body, toc))
    # The address index links to every record, so it is filled in once all
    # records know their page.
    for page, body, toc in rendered_pages:
        if ADDRESSES.search(body):
            parts, export = address_index(topics, "../" * page.depth)
            body = ADDRESSES.sub(lambda m: parts[m.group(1)], body)
            (out / "symbols.json").write_text(json.dumps(export, indent=1), encoding="utf-8")
        body = rewrite_links(body, page, slugs)
        body = DIAGRAM.sub(lambda m: diagram_html(m.group(1), m.group(2)), body)
        if "{{diagram:" in body:
            raise SystemExit(f"{page.source.name}: a diagram placeholder must be a paragraph of its own")
        body = re.sub(r"<table\b", '<div class="table-wrap"><table', body).replace("</table>", "</table></div>")
        prefix = "../" * page.depth
        body = body.replace('src="img/', f'src="{prefix}img/')
        version = release["tag"] if release else ""
        source = page.source.relative_to(ROOT).as_posix()
        values = {
            "title": "Pinyon Shift" if not page.slug else f"{page.title} · Pinyon Shift",
            "description": html.escape(page.description, quote=True),
            "root": prefix or "./",
            "nav": nav_html(pages, page, prefix),
            "toc": toc_html(flatten_toc(toc)),
            "content": body,
            "pager": pager_html(pages, page),
            "repo": REPO_URL,
            "version": html.escape(version),
            "edit": f'<a href="{REPO_URL}/edit/{BRANCH}/{source}">Edit this page on GitHub</a>',
            "page_class": "home" if not page.slug else "doc",
            "channel": channel_note(release),
        }
        rendered = re.sub(r"\{\{(\w+)\}\}", lambda m: values[m.group(1)], template)
        target = out / page.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(rendered, encoding="utf-8")

    (out / ".nojekyll").write_text("", encoding="utf-8")
    # GitHub Pages serves 404.html at any depth, so its links are absolute.
    base = os.environ.get("SITE_BASE", "/pinyon-shift/")
    missing = {
        "title": "Not found · Pinyon Shift", "description": "", "root": base,
        "nav": nav_html(pages, None, base), "toc": "", "pager": "", "repo": REPO_URL,
        "content": f'<h1>Page not found</h1><p>Nothing lives at this address. '
                   f'Start from the <a href="{base}">overview</a>.</p>',
        "version": html.escape(release["tag"] if release else ""), "edit": "",
        "page_class": "doc", "channel": "",
    }
    (out / "404.html").write_text(re.sub(r"\{\{(\w+)\}\}", lambda m: missing[m.group(1)], template),
                                  encoding="utf-8")
    print(f"built {len(pages)} pages into {out}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=SITE / "_build")
    build(parser.parse_args().out.resolve())


if __name__ == "__main__":
    main()
