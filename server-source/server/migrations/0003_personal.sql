-- 0003_personal — everything that belongs to one reader rather than to the collection.
--
-- The library is shared; this layer is not. Two users can shelve, rate, annotate and re-cover
-- the same book without touching each other, which is also why the browse endpoints join these
-- rows in: the app gets `myProgress` in one request instead of N.
--
-- `rev` is a per-user, monotonically increasing counter used as the delta-sync cursor. It is
-- allocated as max(rev)+1 inside the same IMMEDIATE transaction that writes the row, so the
-- single-writer guarantee makes it collision-free without a separate counter table.

CREATE TABLE user_book_states (
  user_id     TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  book_id     TEXT NOT NULL REFERENCES books (id) ON DELETE CASCADE,
  shelf       TEXT NOT NULL DEFAULT 'library' CHECK (shelf IN ('library', 'wishlist')),
  status      TEXT NOT NULL DEFAULT 'none' CHECK (status IN ('none', 'reading', 'finished', 'abandoned', 'on_hold')),
  personal_rating     INTEGER CHECK (personal_rating IS NULL OR (personal_rating BETWEEN 0 AND 10)),
  personal_notes      TEXT,
  -- Personal metadata overrides. The shared record stays authoritative for everyone else; the
  -- app asks for the book and receives this user's view already merged.
  override_title      TEXT,
  override_sort_title TEXT,
  override_published_year INTEGER,
  override_publisher  TEXT,
  override_description TEXT,
  override_series_id  TEXT REFERENCES series (id) ON DELETE SET NULL,
  override_series_index REAL,
  override_contrib_json TEXT CHECK (override_contrib_json IS NULL OR json_valid(override_contrib_json)),
  override_tag_ids_json TEXT CHECK (override_tag_ids_json IS NULL OR json_valid(override_tag_ids_json)),
  -- 自定义封面 (personal): a reader's own cover for a book whose embedded one is missing or ugly.
  cover_override_blob_id TEXT REFERENCES blobs (id) ON DELETE SET NULL,
  cover_override_updated_at TEXT,
  progress_percent  REAL CHECK (progress_percent IS NULL OR (progress_percent BETWEEN 0 AND 100)),
  position_json     TEXT CHECK (position_json IS NULL OR json_valid(position_json)),
  position_file_id  TEXT REFERENCES book_files (id) ON DELETE SET NULL,
  started_at        TEXT,
  last_read_at      TEXT,
  finished_at       TEXT,
  last_device_id    TEXT REFERENCES auth_devices (id) ON DELETE SET NULL,
  times_opened      INTEGER NOT NULL DEFAULT 0 CHECK (times_opened >= 0),
  created_at        TEXT NOT NULL,
  updated_at        TEXT NOT NULL,
  rev               INTEGER NOT NULL CHECK (rev >= 1),
  deleted_at        TEXT,
  PRIMARY KEY (user_id, book_id)
) STRICT;

CREATE INDEX ix_user_book_states_shelf ON user_book_states (user_id, shelf, book_id);
CREATE INDEX ix_user_book_states_read ON user_book_states (user_id, last_read_at DESC);
CREATE INDEX ix_user_book_states_status ON user_book_states (user_id, status);
CREATE INDEX ix_user_book_states_rev ON user_book_states (user_id, rev);

-- One row per (user, file, device): a phone and a tablet can be inside different chapters of
-- the same book without overwriting each other, and the merge picks the furthest *progression*.
-- Storing device-level positions separately is also what lets us refuse to trust a remote
-- charOffset that was measured against a different file version.
CREATE TABLE reading_positions (
  id           TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  book_id      TEXT NOT NULL REFERENCES books (id) ON DELETE CASCADE,
  file_id      TEXT REFERENCES book_files (id) ON DELETE SET NULL,
  device_id    TEXT REFERENCES auth_devices (id) ON DELETE SET NULL,
  target_kind  TEXT NOT NULL CHECK (target_kind IN ('char', 'page', 'percent', 'epub-cfi', 'cfml', 'locator', 'cbml')),
  target_value TEXT NOT NULL,
  label        TEXT,
  percent      REAL CHECK (percent IS NULL OR (percent BETWEEN 0 AND 100)),
  created_at   TEXT NOT NULL,
  updated_at   TEXT NOT NULL,
  UNIQUE (user_id, file_id, device_id)
) STRICT;

CREATE INDEX ix_reading_positions_book ON reading_positions (user_id, book_id);

-- Append-only trail behind the "跳到上次读过的地方" affordance and the recovery path when a
-- sync merges the wrong side. Trimmed by the sweeper (90 days / 500 rows per user).
CREATE TABLE position_history (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id      TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  book_id      TEXT NOT NULL REFERENCES books (id) ON DELETE CASCADE,
  file_id      TEXT REFERENCES book_files (id) ON DELETE SET NULL,
  device_id    TEXT REFERENCES auth_devices (id) ON DELETE SET NULL,
  target_kind  TEXT NOT NULL,
  target_value TEXT NOT NULL,
  percent      REAL,
  at           TEXT NOT NULL
) STRICT;

CREATE INDEX ix_position_history_user ON position_history (user_id, at DESC);

-- Bookmarks/notes. `id` may be client-generated (the app creates them offline), so it is
-- validated as an id at the boundary rather than assumed.
CREATE TABLE bookmarks (
  id           TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  book_id      TEXT NOT NULL REFERENCES books (id) ON DELETE CASCADE,
  file_id      TEXT REFERENCES book_files (id) ON DELETE SET NULL,
  target_kind  TEXT NOT NULL,
  target_value TEXT NOT NULL,
  title        TEXT,
  excerpt      TEXT,
  -- Hash of the surrounding text. When the file version changes, the offset is meaningless but
  -- the snippet can be fuzzy-searched to re-anchor; failing that the bookmark is marked orphaned
  -- and stays visible instead of vanishing.
  snippet_hash TEXT,
  note         TEXT,
  color        TEXT,
  created_at   TEXT NOT NULL,
  updated_at   TEXT NOT NULL,
  rev          INTEGER NOT NULL CHECK (rev >= 1),
  deleted_at   TEXT
) STRICT;

CREATE UNIQUE INDEX ux_bookmarks_anchor ON bookmarks (user_id, book_id, target_value)
  WHERE deleted_at IS NULL;
CREATE INDEX ix_bookmarks_user_rev ON bookmarks (user_id, rev);
CREATE INDEX ix_bookmarks_book ON bookmarks (user_id, book_id) WHERE deleted_at IS NULL;
