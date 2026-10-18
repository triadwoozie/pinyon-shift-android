import importlib.util
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "clone-fh1-car.py"
SPEC = importlib.util.spec_from_file_location("clone_fh1_car", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def database(path: Path) -> None:
    """A few of the game's car tables with one car, 12, laid out as the
    game's are: the body is 12000, physics and upgrades 12000 + n."""
    db = sqlite3.connect(path)
    db.executescript("""
        create table Data_Car (Id integer primary key, DisplayName text, ModelShort text,
            ModelId int, MakeID int, BaseCost int, PerformanceIndex real, ClassID int,
            GameTorqueScale real, CurbWeight real, IsSelectable int, IsUnicorn int,
            ContentId int, Year int);
        create table Data_CarBody (Id integer primary key, ModelWheelbase real);
        create table CarClasses (Id integer primary key, MaxPerformanceIndex real,
            MaxDisplayPerformanceIndex int);
        create table Combo_Colors (ID integer primary key, Ordinal int, Sequence int);
        create table List_SpringDamperPhysics (SpringDamperPhysicsID integer primary key,
            Ordinal int, DefRideHeight real);
        create table List_UpgradeSpringDamper (Id integer primary key, Ordinal int,
            FrontSpringDamperPhysicsID int, RearSpringDamperPhysicsID int);
        create table List_UpgradeEngine (Id integer primary key, Ordinal int, EngineID int);
        create table List_UpgradeCarBodyWeight (Id integer primary key, CarBodyId int);
        insert into Data_Car values (12, '_&1', '_&2', 12, 13, 1000, 0.5, 2, 0.8, 10.0, 0, 1, 7, 2001);
        insert into Data_CarBody values (12000, 2.5);
        insert into CarClasses values (0, 0.005, 99), (1, 0.459, 200), (2, 0.539, 300),
            (3, 0.5975, 400), (4, 0.6505, 500), (5, 0.739, 600);
        insert into Combo_Colors values (12001, 12, 1), (12002, 12, 2);
        insert into List_SpringDamperPhysics values (12000, 12, 0.1), (12100, 12, 0.2);
        insert into List_UpgradeSpringDamper values (12000, 12, 12000, 12100);
        insert into List_UpgradeEngine values (12000, 12, 12);
        insert into List_UpgradeCarBodyWeight values (12003, 12000);
    """)
    db.commit()
    db.close()


class CloneFh1CarTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.db = Path(directory.name) / "gamedb.slt"
        database(self.db)

    def apply(self, cars):
        sql, names = MODULE.build({"car": cars}, self.db)
        db = sqlite3.connect(self.db)
        self.addCleanup(db.close)
        db.executescript(sql)
        return db, names

    def test_copies_every_row_and_renumbers_only_the_cars_own_ids(self):
        db, _ = self.apply([{"id": 30, "source": 12, "name": "Copy"}])
        self.assertEqual(db.execute("select Id, ModelId, MakeID from Data_Car where Id = 30").fetchone(),
                         (30, 30, 13))
        self.assertEqual(db.execute("select Id from Data_CarBody where Id = 30000").fetchone(), (30000,))
        self.assertEqual(db.execute("select ID, Ordinal from Combo_Colors where Ordinal = 30 order by ID").fetchall(),
                         [(30001, 30), (30002, 30)])
        self.assertEqual(db.execute("select * from List_UpgradeSpringDamper where Ordinal = 30").fetchone(),
                         (30000, 30, 30000, 30100))
        # The engine is shared by id even when it equals the car's number.
        self.assertEqual(db.execute("select * from List_UpgradeEngine where Ordinal = 30").fetchone(),
                         (30000, 30, 12))
        self.assertEqual(db.execute("select * from List_UpgradeCarBodyWeight where CarBodyId = 30000").fetchone(),
                         (30003, 30000))
        self.assertEqual(db.execute("select count(*) from Combo_Colors where Ordinal = 12").fetchone(), (2,))

    def test_applies_the_spec_and_names_the_car(self):
        db, names = self.apply([{"id": 30, "source": 12, "name": "Pinyon GT", "short_name": "GT",
                                 "price": 5000, "year": 2013, "torque_scale": 1.2,
                                 "performance_index": 0.6}])
        strings = MODULE._strings_module()
        hash_ = strings.table_hash("Data_Car") << 16
        row = db.execute("select DisplayName, ModelShort, BaseCost, Year, GameTorqueScale, "
                         "PerformanceIndex, ClassID, IsSelectable, IsUnicorn, ContentId "
                         "from Data_Car where Id = 30").fetchone()
        self.assertEqual(row[0], f"_&{hash_ | strings.string_key('IDS_PinyonCar_30')}")
        self.assertEqual(row[1], f"_&{hash_ | strings.string_key('IDS_PinyonCarShort_30')}")
        self.assertEqual(row[2:4], (5000, 2013))
        self.assertAlmostEqual(row[4], 0.96)
        self.assertEqual(row[5:], (0.6, 4, 1, 0, 0))
        self.assertEqual(names, {"IDS_PinyonCar_30": "Pinyon GT", "IDS_PinyonCarShort_30": "GT"})

    def test_estimates_the_index_from_the_torque(self):
        db, _ = self.apply([{"id": 30, "source": 12, "name": "Strong", "torque_scale": 1.5}])
        index, class_id, weight = db.execute(
            "select PerformanceIndex, ClassID, CurbWeight from Data_Car where Id = 30").fetchone()
        self.assertAlmostEqual(index, 0.5 + 0.20 * 0.405465, places=5)
        self.assertEqual((class_id, weight), (3, 10.0))

    def test_refuses_a_weight_scale(self):
        # The game weighs a car from its body and upgrade data, not CurbWeight.
        with self.assertRaisesRegex(MODULE.CloneError, "weight_scale has no effect"):
            MODULE.build({"car": [{"id": 30, "source": 12, "name": "Light",
                                    "weight_scale": 0.8}]}, self.db)

    def test_converts_the_shown_index(self):
        # The game shows 0.72 as A 579 (class 5, 500 at 0.6505, 600 at 0.739).
        db, _ = self.apply([{"id": 30, "source": 12, "name": "Shown", "pi": 579}])
        index, class_id = db.execute("select PerformanceIndex, ClassID from Data_Car where Id = 30").fetchone()
        self.assertAlmostEqual(index, 0.6505 + 78.75 * 0.0885 / 100, places=6)
        self.assertEqual(class_id, 5)

    def test_refuses_a_taken_id_or_a_missing_source(self):
        with self.assertRaisesRegex(MODULE.CloneError, "taken"):
            MODULE.build({"car": [{"id": 12, "source": 12, "name": "Same"}]}, self.db)
        with self.assertRaisesRegex(MODULE.CloneError, "not in the database"):
            MODULE.build({"car": [{"id": 30, "source": 99, "name": "Nothing"}]}, self.db)
        with self.assertRaisesRegex(MODULE.CloneError, "share an id"):
            MODULE.build({"car": [{"id": 30, "source": 12, "name": "A"},
                                  {"id": 30, "source": 12, "name": "B"}]}, self.db)


if __name__ == "__main__":
    unittest.main()
