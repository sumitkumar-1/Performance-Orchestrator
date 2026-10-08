-- Historical migrations stay immutable so existing installations can upgrade.
-- Application plans, runs, events and production profiles are retained.
DROP TABLE IF EXISTS simulated_loads;
DROP TABLE IF EXISTS simulated_deployments;
DROP TABLE IF EXISTS profile_revisions;
DROP TABLE IF EXISTS profiles;
