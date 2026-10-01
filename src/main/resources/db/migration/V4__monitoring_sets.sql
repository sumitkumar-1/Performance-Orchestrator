CREATE TABLE monitoring_sets (
  id VARCHAR(64) PRIMARY KEY,
  revision INTEGER NOT NULL,
  environment VARCHAR(100) NOT NULL,
  body CLOB NOT NULL
);
