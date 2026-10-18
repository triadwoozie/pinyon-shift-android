#!/usr/bin/env python3
"""Add cars that reuse an existing model, as a database mod (#505).

A spec lists the new cars:

  [[car]]
  id = 1700                 # new car id, unused by the game and its DLC
  source = 1496             # the car whose model, physics and upgrades it copies
  name = "Pinyon XK"        # shown name; added to Data_Car.str as IDS_PinyonCar_<id>
  short_name = "XK"         # optional, the short model name
  year = 2013               # optional
  price = 85000             # optional, BaseCost in credits
  torque_scale = 1.15       # optional, multiplies the source's GameTorqueScale
  pi = 598                  # optional, the Autoshow's index as shown (101-999)
  performance_index = 0.73  # optional, the same as the raw index; without
                            # either, estimated from torque_scale

and the tool writes, into a mod directory:

  db/50-clone-cars.sql        copies of the source car's rows under the new id
  strings/ALL/Data_Car.toml   the names, in every language

The SQL copies rows inside the player's own database when the mod is built
(build-mod-patches.py), so the mod carries no game data. A car is its rows in
about 30 tables (the set every DLC merge database adds per car): Data_Car,
Data_CarBody (id x 1000), colours, camera, part positions, anti-roll and
spring physics and the per-car List_Upgrade* tables. Their own ids are
renumbered as the game's DLC clones are: the car id itself, and car x 1000 + n
for its body, colours, physics and upgrade rows. Engines, drivetrains, torque
curves, aero and wheels stay shared by id.

The index and class written here are what the Autoshow lists. Once the car
is bought the game computes its index from the car's physics and shows that
instead, so a written index the physics do not produce lists the car in one
class and delivers it in another. Without an explicit index the source's is
moved by 0.20 per unit of ln(torque_scale), the slope measured on bought
clones (an Exige at 1.2 and a 370Z at 1.1, within 3 shown points); a car
held back by a speed limiter gains less (an XKR-S at 1.15 gained 0.15 per
unit). To list a car exactly, buy it once, read the index the game shows
and give it as pi. The game shows the index linearly within its class,
from the previous class's MaxPerformanceIndex and MaxDisplayPerformanceIndex
to this class's, rounded up (0.72 shows as A 579), and the class is the first
CarClasses row whose MaxPerformanceIndex is at or above it, as for every
base car.

Data_Car.CurbWeight is not the car's weight in the game: a clone with it
scaled weighed and rated the same as its source, so the spec has no
weight_scale.

  clone-fh1-car.py cars.toml <state>/mods/<mod> [--game-db gamedb.slt]
"""
from __future__ import annotations

import argparse
import importlib.util
import math
import sqlite3
import sys
import tomllib
from pathlib import Path

DEFAULT_DB = Path(".local/game/base/media/db/gamedb.slt")
SQL_NAME = "50-clone-cars.sql"
STRINGS_TABLE = "Data_Car"
# Slope of a bought clone's PerformanceIndex over ln(torque_scale), as the
# game computes it (#505).
PI_PER_LOG_TORQUE = 0.20

# Tables holding a car's own rows, with the column that names the car (or
# its body, id x 1000).
CAR_TABLES = {
    "Data_Car": "Id",
    "Data_CarBody": "Id",
    "CameraOverrides": "CarId",
    "CarExceptions": "CarID",
    "CarTrackOffsets": "Id",
    "CarPartPositions": "Ordinal",
    "Combo_Colors": "Ordinal",
    "List_AntiSwayPhysics": "Ordinal",
    "List_SpringDamperPhysics": "Ordinal",
    "List_UpgradeAntiSwayFront": "Ordinal",
    "List_UpgradeAntiSwayRear": "Ordinal",
    "List_UpgradeBrakes": "Ordinal",
    "List_UpgradeCarBody": "Ordinal",
    "List_UpgradeDrivetrain": "Ordinal",
    "List_UpgradeEngine": "Ordinal",
    "List_UpgradeRearWing": "Ordinal",
    "List_UpgradeRimSizeFront": "Ordinal",
    "List_UpgradeRimSizeRear": "Ordinal",
    "List_UpgradeSpringDamper": "Ordinal",
    "List_UpgradeTireCompound": "Ordinal",
    "List_UpgradeCarBodyChassisStiffness": "CarbodyId",
    "List_UpgradeCarBodyFrontBumper": "CarBodyID",
    "List_UpgradeCarBodyHood": "CarBodyID",
    "List_UpgradeCarBodyRearBumper": "CarBodyID",
    "List_UpgradeCarBodySideSkirt": "CarBodyID",
    "List_UpgradeCarBodyTireWidthFront": "CarBodyId",
    "List_UpgradeCarBodyTireWidthRear": "CarBodyId",
    "List_UpgradeCarBodyWeight": "CarBodyId",
}
# Columns that hold the car's own ids: renumbered when they equal the car id
# or fall in car x 1000 .. car x 1000 + 999. Everything else is copied.
OWN_ID_COLUMNS = {
    "id", "carid", "ordinal", "modelid", "carbodyid", "antiswayphysicsid",
    "springdamperphysicsid", "frontspringdamperphysicsid", "rearspringdamperphysicsid",
}
BODY_KEYED = {"Data_CarBody"} | {table for table, key in CAR_TABLES.items()
                                 if key.lower() == "carbodyid"}


class CloneError(ValueError):
    pass


def _strings_module():
    path = Path(__file__).with_name("fh1-strings.py")
    spec = importlib.util.spec_from_file_location("fh1_strings", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules.setdefault(spec.name, module)
    assert spec.loader
    spec.loader.exec_module(module)
    return module


def _quote(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def string_name(car_id: int, short: bool = False) -> str:
    return f"IDS_PinyonCar{'Short' if short else ''}_{car_id}"


def renumber(column: str, source: int, target: int) -> str:
    c = _quote(column)
    return (f"CASE WHEN {c} = {source} THEN {target} "
            f"WHEN {c} BETWEEN {source * 1000} AND {source * 1000 + 999} "
            f"THEN {c} - {source * 1000} + {target * 1000} ELSE {c} END")


def raw_index_sql(shown: int) -> str:
    """SQL for the raw index the game shows as `shown`: linear within the
    class whose display range holds it. The game rounds the shown index up
    (an exact 197 is listed as 198), so aim a quarter point below."""
    upper = (f"(select Id from CarClasses where MaxDisplayPerformanceIndex >= {shown} "
             "order by Id limit 1)")
    low = (f"(select {{0}} from CarClasses where Id < {upper} order by Id desc limit 1)")
    high = f"(select {{0}} from CarClasses where Id = {upper})"
    low_raw, low_shown = low.format("MaxPerformanceIndex"), low.format("MaxDisplayPerformanceIndex")
    high_raw, high_shown = high.format("MaxPerformanceIndex"), high.format("MaxDisplayPerformanceIndex")
    return (f"{low_raw} + ({shown} - 0.25 - {low_shown}) * ({high_raw} - {low_raw}) "
            f"/ ({high_shown} - {low_shown})")


def estimated_index_shift(torque_scale: float) -> float:
    return PI_PER_LOG_TORQUE * math.log(torque_scale)


def car_sql(db: sqlite3.Connection, car: dict, strings) -> str:
    try:
        target, source = int(car["id"]), int(car["source"])
        name = str(car["name"])
    except KeyError as error:
        raise CloneError(f"a car needs {error.args[0]}") from None
    if not 0 < target < 4000:
        raise CloneError(f"car {target}: id must be a new number below 4000")
    if not db.execute("select 1 from Data_Car where Id = ?", (source,)).fetchone():
        raise CloneError(f"car {target}: source {source} is not in the database")
    if db.execute("select 1 from Data_Car where Id = ?", (target,)).fetchone():
        raise CloneError(f"car {target}: the id is taken")
    torque = float(car.get("torque_scale", 1.0))
    if torque <= 0:
        raise CloneError(f"car {target}: torque_scale must be positive")
    if "weight_scale" in car:
        raise CloneError(f"car {target}: weight_scale has no effect in the game, "
                         "which does not weigh a car by Data_Car.CurbWeight")

    lines = [f"-- {name}: car {target}, a copy of car {source}"]
    for table, key in CAR_TABLES.items():
        columns = [row[1] for row in db.execute(f"pragma table_info({_quote(table)})")]
        if not columns:
            continue
        match = source * 1000 if table in BODY_KEYED else source
        values = []
        for column in columns:
            if column.lower() in OWN_ID_COLUMNS:
                values.append(renumber(column, source, target))
            else:
                values.append(_quote(column))
        lines.append(
            f"insert into {_quote(table)} ({', '.join(map(_quote, columns))})\n"
            f"  select {', '.join(values)}\n"
            f"  from {_quote(table)} where {_quote(key)} = {match};")

    table_hash = strings.table_hash(STRINGS_TABLE)
    sets = [f"DisplayName = '_&{(table_hash << 16) | strings.string_key(string_name(target))}'"]
    if "short_name" in car:
        key = strings.string_key(string_name(target, short=True))
        sets.append(f"ModelShort = '_&{(table_hash << 16) | key}'")
    if "year" in car:
        sets.append(f"Year = {int(car['year'])}")
    if "price" in car:
        sets.append(f"BaseCost = {int(car['price'])}")
    if torque != 1.0:
        sets.append(f"GameTorqueScale = GameTorqueScale * {torque!r}")
    if "pi" in car:
        shown = int(car["pi"])
        if not 100 < shown <= 999:
            raise CloneError(f"car {target}: pi is between 101 and 999")
        sets.append(f"PerformanceIndex = {raw_index_sql(shown)}")
    elif "performance_index" in car:
        index = float(car["performance_index"])
        if not 0 < index <= 1:
            raise CloneError(f"car {target}: performance_index is between 0 and 1")
        sets.append(f"PerformanceIndex = {index!r}")
    elif torque != 1.0:
        shift = estimated_index_shift(torque)
        sets.append(f"PerformanceIndex = min(1.0, max(0.005, PerformanceIndex + {shift:.6f}))")
    # Sold in the Autoshow as base content.
    sets += ["IsSelectable = 1", "IsUnicorn = 0", "ContentId = 0"]
    lines.append(f"update Data_Car set {', '.join(sets)} where Id = {target};")
    lines.append("update Data_Car set ClassID = (select min(Id) from CarClasses "
                 "where MaxPerformanceIndex >= Data_Car.PerformanceIndex) "
                 f"where Id = {target};")
    return "\n".join(lines) + "\n"


def build(spec: dict, db_path: Path) -> tuple[str, dict[str, str]]:
    strings = _strings_module()
    cars = spec.get("car", [])
    if not cars:
        raise CloneError("the spec lists no [[car]]")
    ids = [int(car.get("id", 0)) for car in cars]
    if len(set(ids)) != len(ids):
        raise CloneError("two cars share an id")
    db = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
    try:
        sql = ["-- Generated by tools/clone-fh1-car.py; rows are copied from the",
               "-- player's own database when the mod is built.", "begin;"]
        names: dict[str, str] = {}
        for car in cars:
            sql.append(car_sql(db, car, strings))
            names[string_name(int(car["id"]))] = str(car["name"])
            if "short_name" in car:
                names[string_name(int(car["id"]), short=True)] = str(car["short_name"])
        sql.append("commit;")
    finally:
        db.close()
    return "\n".join(sql) + "\n", names


def toml_string(text: str) -> str:
    escaped = text.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("spec", type=Path, help="the cars' TOML spec")
    parser.add_argument("mod", type=Path, help="the mod directory to write into")
    parser.add_argument("--game-db", type=Path, default=DEFAULT_DB,
                        help="the player's media/db/gamedb.slt, read for its schema and ids")
    args = parser.parse_args(argv)
    try:
        with args.spec.open("rb") as handle:
            spec = tomllib.load(handle)
        sql, names = build(spec, args.game_db)
    except (OSError, tomllib.TOMLDecodeError, CloneError, sqlite3.Error) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    (args.mod / "db").mkdir(parents=True, exist_ok=True)
    (args.mod / "db" / SQL_NAME).write_text(sql, encoding="utf-8", newline="\n")
    strings_dir = args.mod / "strings" / "ALL"
    strings_dir.mkdir(parents=True, exist_ok=True)
    (strings_dir / f"{STRINGS_TABLE}.toml").write_text(
        "".join(f"{name} = {toml_string(text)}\n" for name, text in names.items()),
        encoding="utf-8", newline="\n")
    print(f"wrote {len(names)} names and {SQL_NAME} for {len(spec['car'])} cars")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
