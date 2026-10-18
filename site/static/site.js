// Mobile menu, theme switch, code copy buttons and the current section in
// the page outline. The site works without it.
(() => {
  const root = document.documentElement;

  const menu = document.querySelector(".menu-button");
  const setMenu = (open) => {
    document.body.classList.toggle("nav-open", open);
    menu.setAttribute("aria-expanded", String(open));
  };
  menu.addEventListener("click", () => setMenu(!document.body.classList.contains("nav-open")));
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") setMenu(false);
  });

  const dark = () => root.dataset.theme
    ? root.dataset.theme === "dark"
    : matchMedia("(prefers-color-scheme: dark)").matches;
  document.querySelector(".theme-button").addEventListener("click", () => {
    const theme = dark() ? "light" : "dark";
    root.dataset.theme = theme;
    try { localStorage.setItem("theme", theme); } catch (error) { /* private mode */ }
  });

  for (const block of document.querySelectorAll(".prose .code")) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "copy";
    button.textContent = "Copy";
    button.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(block.querySelector("pre").innerText.replace(/\n$/, ""));
        button.textContent = "Copied";
      } catch (error) {
        button.textContent = "Select and copy";
      }
      setTimeout(() => { button.textContent = "Copy"; }, 1600);
    });
    block.append(button);
  }

  // One filter for every reverse-engineering table on the page: text matches
  // addresses, names and descriptions; the select narrows by confidence.
  const tables = [...document.querySelectorAll(".prose table.findings")];
  if (tables.length) {
    const bar = document.createElement("div");
    bar.className = "findings-filter";
    bar.innerHTML = '<input type="search" placeholder="Filter by address, name or text" aria-label="Filter records">' +
      '<select aria-label="Confidence"><option value="">Any confidence</option><option value="confirmed">Confirmed</option>' +
      '<option value="probable">Probable</option><option value="guess">Guess</option></select><output></output>';
    tables[0].closest(".table-wrap").before(bar);
    const [input, select, count] = bar.children;
    const rows = tables.flatMap((table) => [...table.tBodies[0].rows]);
    const text = new Map(rows.map((row) => [row, row.textContent.toLowerCase()]));
    const apply = () => {
      const words = input.value.toLowerCase().split(/\s+/).filter(Boolean);
      let shown = 0;
      for (const row of rows) {
        const match = words.every((word) => text.get(row).includes(word)) &&
          (!select.value || row.dataset.confidence === select.value);
        row.classList.toggle("findings-hidden", !match);
        shown += match;
      }
      count.value = `${shown} of ${rows.length}`;
    };
    input.addEventListener("input", apply);
    select.addEventListener("change", apply);
    apply();
  }

  const links = [...document.querySelectorAll(".toc a")];
  const headings = links.map((link) => document.getElementById(link.hash.slice(1))).filter(Boolean);
  if (headings.length && "IntersectionObserver" in window) {
    const visible = new Set();
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) visible.add(entry.target); else visible.delete(entry.target);
      }
      const current = headings.find((heading) => visible.has(heading));
      if (!current) return;
      for (const link of links) link.classList.toggle("active", link.hash === `#${current.id}`);
    }, { rootMargin: "-56px 0px -65% 0px" });
    headings.forEach((heading) => observer.observe(heading));
  }
})();
