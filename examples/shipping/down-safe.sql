ALTER TABLE shipments ADD COLUMN instructions TEXT;
UPDATE shipments SET instructions = (
  SELECT saved.instructions FROM migration_instructions AS saved
  WHERE saved.shipment_id = shipments.id
);
DROP TABLE migration_instructions;
