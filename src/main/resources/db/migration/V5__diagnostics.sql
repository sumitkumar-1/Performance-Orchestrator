CREATE TABLE diagnostic_traces (id VARCHAR(64) PRIMARY KEY, created_at VARCHAR(40) NOT NULL, plan_id VARCHAR(64), run_id VARCHAR(64), parent_id VARCHAR(64));
CREATE INDEX diagnostic_plan ON diagnostic_traces(plan_id);
CREATE INDEX diagnostic_run ON diagnostic_traces(run_id);
CREATE TABLE diagnostic_operations (id VARCHAR(64) PRIMARY KEY, trace_id VARCHAR(64) NOT NULL REFERENCES diagnostic_traces(id), started_at VARCHAR(40) NOT NULL, finished_at VARCHAR(40), kind VARCHAR(20) NOT NULL, summary VARCHAR(2000) NOT NULL, outcome VARCHAR(200), duration_ms BIGINT);
CREATE INDEX diagnostic_operations_trace ON diagnostic_operations(trace_id, started_at);
