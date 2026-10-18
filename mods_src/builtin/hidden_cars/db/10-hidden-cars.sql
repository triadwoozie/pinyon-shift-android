-- #484: 22 cars are drivable (IsDrivable = 1) but never listed
-- (IsSelectable = 0): traffic cars such as the tour bus, the Kenworth
-- truck, the Leaf and the Prius, and the showcase Mini, 500 Abarth and
-- 205 T16. Each has its model, physics and upgrade rows. Listing them
-- puts them in the Autoshow under their make. Id 1215 is the placeholder
-- NUL_Car_00 and stays hidden.
update Data_Car set IsSelectable = 1
  where IsSelectable = 0 and IsDrivable = 1 and Id <> 1215;

-- Seven of them cost nothing in the database; give them a price in line
-- with their class and the other traffic cars.
update Data_Car set BaseCost = 9000 where Id = 1530 and BaseCost = 0;   -- RAV4 Sport
update Data_Car set BaseCost = 9000 where Id = 1531 and BaseCost = 0;   -- A4 Avant
update Data_Car set BaseCost = 11000 where Id = 1532 and BaseCost = 0;  -- Camry
update Data_Car set BaseCost = 14000 where Id = 1541 and BaseCost = 0;  -- Explorer
update Data_Car set BaseCost = 30000 where Id = 1542 and BaseCost = 0;  -- F-350 Super Duty
update Data_Car set BaseCost = 6000 where Id = 1543 and BaseCost = 0;   -- Focus ZX4
update Data_Car set BaseCost = 100000 where Id = 1529 and BaseCost = 0; -- tour bus
