DROP TABLE IF EXISTS MONITOR_LOG;
DROP INDEX IF EXISTS MONITOR_LOG_INDEX;

CREATE TABLE IF NOT EXISTS LOG_ENTRY (
    SERVICE VARCHAR(32),
    STATUS VARCHAR(32),
    IP VARCHAR(32),
    PORT INTEGER,
    TS VARCHAR(32));

CREATE INDEX IF NOT EXISTS MONITOR_LOG_INDEX ON LOG_ENTRY (SERVICE);