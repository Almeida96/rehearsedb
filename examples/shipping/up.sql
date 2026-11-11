CREATE TABLE migration_instructions(shipment_id INTEGER PRIMARY KEY, instructions TEXT);
INSERT INTO migration_instructions SELECT id, instructions FROM shipments;
ALTER TABLE shipments DROP COLUMN instructions;
