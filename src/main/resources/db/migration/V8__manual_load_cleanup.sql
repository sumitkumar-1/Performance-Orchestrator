CREATE TABLE stopped_baseline_loads (
  run_id VARCHAR(64) PRIMARY KEY REFERENCES runs(id)
);
