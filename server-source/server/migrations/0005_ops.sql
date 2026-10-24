-- 0005_ops — audit trail, settings, and the single-row instance identity.

CREATE TABLE operation_logs (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  at            TEXT NOT NULL,
  actor_user_id TEXT REFERENCES users (id) ON DELETE SET NULL,
  action        TEXT NOT NULL,
  target_type   TEXT,
  target_id     TEXT,
  book_id       TEXT,
  detail_json   TEXT CHECK (detail_json IS NULL OR json_valid(detail_json)),
  ip            TEXT,
  user_agent    TEXT,
  request_id    TEXT,
  result        TEXT NOT NULL DEFAULT 'ok' CHECK (result IN ('ok', 'denied', 'error')),
  -- Retention-bounded by the sweeper; the point is "who deleted this", not a permanent record.
  expires_at    TEXT
) STRICT;

CREATE INDEX ix_operation_logs_at ON operation_logs (at DESC);
CREATE INDEX ix_operation_logs_actor ON operation_logs (actor_user_id, at DESC);
CREATE INDEX ix_operation_logs_target ON operation_logs (target_type, target_id, at DESC);
CREATE INDEX ix_operation_logs_action ON operation_logs (action, at DESC);

CREATE TABLE settings (
  key         TEXT PRIMARY KEY,
  value_json  TEXT NOT NULL CHECK (json_valid(value_json)),
  updated_at  TEXT NOT NULL,
  updated_by  TEXT REFERENCES users (id) ON DELETE SET NULL
) STRICT;

-- Exactly one row. `instance_id` lets a client notice it is talking to a different server than
-- it downloaded from (rebuilt container, restored backup) and drop caches that are no longer
-- valid, which a version string alone cannot express.
CREATE TABLE app_state (
  id             INTEGER PRIMARY KEY CHECK (id = 1),
  instance_id    TEXT NOT NULL,
  schema_version INTEGER NOT NULL,
  installed_at   TEXT NOT NULL,
  last_backup_at TEXT
) STRICT;
