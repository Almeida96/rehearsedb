CREATE TABLE shipments(id INTEGER PRIMARY KEY, destination TEXT NOT NULL, instructions TEXT);
INSERT INTO shipments(id, destination, instructions) VALUES
  (1, 'North depot', 'Use loading bay 3'),
  (2, 'Island depot', 'Call before ferry transfer'),
  (3, 'South depot', NULL);
