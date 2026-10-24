-- 0001_identity — users, devices/sessions, brute-force bookkeeping.
--
-- Conventions for every migration in this directory:
--   * STRICT tables. Binding a number into a TEXT column (or vice versa) then fails loudly at
--     the statement instead of storing a value that later sorts and compares wrongly.
--   * Boolean-ish columns are INTEGER with CHECK (x IN (0,1)); SQLite has no boolean type.
--   * Timestamps are ISO-8601 UTC TEXT, so they are lexically sortable and directly readable
--     in a database browser during support.
--   * Soft deletes use deleted_at, never a hard DELETE, so /sync/delta can hand a tombstone to
--     an offline client instead of it keeping a row that vanished server-side.

CREATE TABLE users (
  id               TEXT PRIMARY KEY,
  username         TEXT NOT NULL COLLATE NOCASE,
  display_name     TEXT NOT NULL,
  email            TEXT COLLATE NOCASE,
  password_hash    TEXT NOT NULL,
  role             TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('admin', 'user')),
  status           TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
  -- Grants beyond the role; may only narrow a normal user or widen a specific capability for a
  -- trusted non-admin. Computed into `capabilities` at request time, never trusted raw.
  permissions_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(permissions_json)),
  -- Reader preferences the app syncs so a second device opens a book the same way.
  prefs_json       TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(prefs_json)),
  -- NULL = unlimited. Enforced when a blob is committed, not when the upload starts.
  quota_bytes      INTEGER CHECK (quota_bytes IS NULL OR quota_bytes >= 0),
  -- Bumped on password change and on role/status change, which invalidates every access token
  -- in flight without waiting for it to expire.
  token_version    INTEGER NOT NULL DEFAULT 1 CHECK (token_version >= 1),
  failed_attempts  INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  locked_until     TEXT,
  must_change_pw   INTEGER NOT NULL DEFAULT 0 CHECK (must_change_pw IN (0, 1)),
  last_login_at    TEXT,
  created_at       TEXT NOT NULL,
  updated_at       TEXT NOT NULL
) STRICT;

CREATE UNIQUE INDEX ux_users_username ON users (username);
CREATE UNIQUE INDEX ux_users_email ON users (email) WHERE email IS NOT NULL;

-- One row per credential lineage. `kind` separates an interactive login from a paired device
-- and from an API token, because they rotate and expire on different rules even though the
-- validation path is identical.
CREATE TABLE auth_devices (
  id               TEXT PRIMARY KEY,
  user_id          TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  kind             TEXT NOT NULL CHECK (kind IN ('session', 'device', 'api')),
  label            TEXT,
  platform         TEXT CHECK (platform IS NULL OR platform IN ('android', 'ios', 'web', 'cli', 'other')),
  client_info_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(client_info_json)),
  -- The refresh token itself is never stored: only SHA-256 of it, so a database dump cannot
  -- mint credentials.
  refresh_hash     TEXT NOT NULL,
  -- Rotation lineage. Presenting a token that was already rotated means either a replay or a
  -- stolen copy, and the whole family is revoked on the spot.
  family_id        TEXT NOT NULL,
  rotated_from_id  TEXT REFERENCES auth_devices (id) ON DELETE SET NULL,
  scopes_json      TEXT NOT NULL DEFAULT '["*"]' CHECK (json_valid(scopes_json)),
  created_ip       TEXT,
  last_ip          TEXT,
  created_at       TEXT NOT NULL,
  last_used_at     TEXT,
  expires_at       TEXT NOT NULL,
  revoked_at       TEXT
) STRICT;

CREATE UNIQUE INDEX ux_device_refresh ON auth_devices (refresh_hash);
CREATE INDEX ix_device_user ON auth_devices (user_id, revoked_at, expires_at);
CREATE INDEX ix_device_family ON auth_devices (family_id) WHERE revoked_at IS NULL;

-- Brute-force lockout survives a restart without pulling in Redis. The in-memory rate limiter
-- handles bursts; this handles the slow, deliberate password guesser.
CREATE TABLE login_attempts (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  at        TEXT NOT NULL,
  ip        TEXT,
  username  TEXT,
  ok        INTEGER NOT NULL CHECK (ok IN (0, 1)),
  user_agent TEXT
) STRICT;

CREATE INDEX ix_login_attempts_time ON login_attempts (at);
CREATE INDEX ix_login_attempts_subject ON login_attempts (username, at);
