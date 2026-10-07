CREATE TABLE additional_loads (
  id VARCHAR(64) PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL REFERENCES runs(id),
  body CLOB NOT NULL
);
CREATE INDEX additional_loads_run ON additional_loads(run_id);
