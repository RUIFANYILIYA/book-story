-- 0002_library — the shared collection: bytes on disk, and the metadata that describes them.

-- Content-addressed originals. A row is immutable once status='ready': replacing a book's file
-- inserts a NEW blob and flips book_files.blob_id in one transaction rather than overwriting a
-- path. That is what lets a second device's half-finished download keep reading a stable
-- (file_id, sha256) while the first device already swapped the file.
CREATE TABLE blobs (
  id           TEXT PRIMARY KEY,
  sha256       TEXT NOT NULL CHECK (length(sha256) = 64),
  size_bytes   INTEGER NOT NULL CHECK (size_bytes >= 0),
  -- Relative to EBOOK_LIBRARY_DIR, so the volume can be moved or restored elsewhere without a
  -- rewrite and without ever hardcoding /data.
  storage_path TEXT NOT NULL,
  media_type   TEXT NOT NULL,
  format       TEXT NOT NULL,
  -- Reference-counted by book_files and cover references; GC only unlinks at zero.
  refcount     INTEGER NOT NULL DEFAULT 1 CHECK (refcount >= 0),
  created_at   TEXT NOT NULL,
  deleted_at   TEXT
) STRICT;

CREATE UNIQUE INDEX ux_blobs_sha ON blobs (sha256) WHERE deleted_at IS NULL;
CREATE INDEX ix_blobs_refcount ON blobs (refcount) WHERE deleted_at IS NULL;

-- Shared taxonomy, one category per book. `path` is a materialized "/root/parent/self/" so a
-- subtree filter is one LIKE prefix instead of a recursive CTE on every browse request.
CREATE TABLE categories (
  id         TEXT PRIMARY KEY,
  parent_id  TEXT REFERENCES categories (id) ON DELETE SET NULL,
  name       TEXT NOT NULL,
  name_norm  TEXT NOT NULL,
  slug       TEXT NOT NULL,
  path       TEXT NOT NULL,
  position   INTEGER NOT NULL DEFAULT 0,
  icon       TEXT,
  description TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
) STRICT;

CREATE UNIQUE INDEX ux_categories_parent_name ON categories (parent_id, name_norm);
CREATE UNIQUE INDEX ux_categories_slug ON categories (slug);
CREATE INDEX ix_categories_path ON categories (path);

CREATE TABLE series (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  name_norm   TEXT NOT NULL,
  description TEXT,
  credits     TEXT,
  created_at  TEXT NOT NULL
) STRICT;

CREATE UNIQUE INDEX ux_series_name ON series (name_norm);

-- Contributors are deduped by normalized name rather than by an authority id, which is honest
-- about what metadata embedded in ebook files actually gives us.
CREATE TABLE contributors (
  id           TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  name_norm    TEXT NOT NULL,
  display_name TEXT,
  sort_name    TEXT,
  bio          TEXT,
  aliases_json TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(aliases_json)),
  created_at   TEXT NOT NULL,
  updated_at   TEXT NOT NULL
) STRICT;

CREATE UNIQUE INDEX ux_contributors_name ON contributors (name_norm);

CREATE TABLE books (
  id              TEXT PRIMARY KEY,
  title           TEXT NOT NULL,
  -- Casefolded, NFKC, punctuation-stripped. Feeds title sorting, the duplicate guard and LIKE
  -- fallback search; computed by the ingest layer, never edited by hand.
  title_norm      TEXT NOT NULL,
  sort_title      TEXT,
  subtitle        TEXT,
  original_title  TEXT,
  category_id     TEXT REFERENCES categories (id) ON DELETE SET NULL,
  series_id       TEXT REFERENCES series (id) ON DELETE SET NULL,
  series_index    REAL,
  publisher       TEXT,
  published_year  INTEGER,
  language        TEXT,
  description     TEXT,
  isbn            TEXT,
  asin            TEXT,
  page_count      INTEGER,
  word_count      INTEGER,
  -- Denormalized "primary author — title" for sorting without a join on every list request.
  sort_key        TEXT NOT NULL,
  -- Combined haystack (title + authors + tags + series + publisher). Mirrored into books_fts
  -- by trigger, and used directly by LIKE for 1-2 character CJK queries, which the trigram
  -- tokenizer cannot answer.
  search_text     TEXT NOT NULL DEFAULT '',
  -- Which parser produced the current metadata. Re-ingest never overwrites a field the human
  -- touched: see pinned_fields_json.
  metadata_source TEXT NOT NULL CHECK (metadata_source IN
    ('epub', 'fb2', 'mobi', 'pdf', 'docx', 'cbz', 'txt', 'filename', 'manual', 'import')),
  pinned_fields_json TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(pinned_fields_json)),
  -- 1 when the title came from a filename guess or a low-confidence parse; the app and the
  -- admin UI surface these as "待确认" instead of presenting a guess as fact.
  needs_review    INTEGER NOT NULL DEFAULT 0 CHECK (needs_review IN (0, 1)),
  has_cover       INTEGER NOT NULL DEFAULT 0 CHECK (has_cover IN (0, 1)),
  cover_blob_id   TEXT REFERENCES blobs (id) ON DELETE SET NULL,
  cover_updated_at TEXT,
  -- Bumped whenever the bytes or the cover change; the app keys its caches on this.
  content_updated_at TEXT NOT NULL,
  storage_bytes   INTEGER NOT NULL DEFAULT 0 CHECK (storage_bytes >= 0),
  file_count      INTEGER NOT NULL DEFAULT 0 CHECK (file_count >= 0),
  added_at        TEXT NOT NULL,
  updated_at      TEXT NOT NULL,
  deleted_at      TEXT
) STRICT;

CREATE INDEX ix_books_added ON books (added_at DESC, id DESC);
CREATE INDEX ix_books_sort ON books (sort_key, id);
CREATE INDEX ix_books_title_norm ON books (title_norm, id);
CREATE INDEX ix_books_category ON books (category_id);
CREATE INDEX ix_books_series ON books (series_id, series_index);
CREATE INDEX ix_books_updated ON books (updated_at) WHERE deleted_at IS NULL;
CREATE INDEX ix_books_content_updated ON books (content_updated_at);
CREATE INDEX ix_books_needs_review ON books (needs_review) WHERE needs_review = 1;
-- Two identical titles by the same author are a duplicate import, not a coincidence; the
-- ingest path reports 409 duplicate_book and offers to attach the file instead.
CREATE UNIQUE INDEX ux_books_dedupe ON books (title_norm, sort_key) WHERE deleted_at IS NULL;

CREATE TABLE book_files (
  id         TEXT PRIMARY KEY,
  book_id    TEXT NOT NULL REFERENCES books (id) ON DELETE CASCADE,
  blob_id    TEXT NOT NULL REFERENCES blobs (id) ON DELETE RESTRICT,
  format     TEXT NOT NULL CHECK (format IN
    ('epub', 'pdf', 'txt', 'mobi', 'azw', 'azw3', 'kfx', 'fb2', 'fb2z', 'cbz', 'docx', 'doc', 'htmlz', 'unknown')),
  media_type TEXT NOT NULL,
  -- The name offered to clients in Content-Disposition; decoupled from storage_path so a
  -- Chinese filename survives a round trip through an Android download manager.
  filename   TEXT NOT NULL,
  size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
  sha256     TEXT NOT NULL CHECK (length(sha256) = 64),
  -- TXT only: the detected (or user-corrected) charset, persisted so a misread GB18030 novel
  -- is fixed once and never re-detected on every open.
  charset    TEXT,
  page_count INTEGER,
  drm        INTEGER NOT NULL DEFAULT 0 CHECK (drm IN (0, 1)),
  is_primary INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0, 1)),
  status     TEXT NOT NULL DEFAULT 'ready' CHECK (status IN ('ready', 'processing', 'failed', 'quarantined')),
  parse_error TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
) STRICT;

CREATE UNIQUE INDEX ux_book_files_pair ON book_files (book_id, blob_id);
CREATE UNIQUE INDEX ux_book_files_primary ON book_files (book_id) WHERE is_primary = 1;
CREATE INDEX ix_book_files_book ON book_files (book_id, format);
CREATE INDEX ix_book_files_blob ON book_files (blob_id);

CREATE TABLE book_contributors (
  book_id        TEXT NOT NULL REFERENCES books (id) ON DELETE CASCADE,
  contributor_id TEXT NOT NULL REFERENCES contributors (id) ON DELETE CASCADE,
  role           TEXT NOT NULL CHECK (role IN ('author', 'translator', 'illustrator', 'editor', 'narrator', 'compiler')),
  position       INTEGER NOT NULL DEFAULT 0,
  -- The name as it appeared in this book, kept because normalized dedup loses "阿城" vs "阿城 (1949-)".
  name_override  TEXT,
  PRIMARY KEY (book_id, contributor_id, role, position)
) STRICT;

CREATE INDEX ix_book_contributors_contrib ON book_contributors (contributor_id, role);
CREATE INDEX ix_book_contributors_role ON book_contributors (role, book_id);

CREATE VIEW v_book_authors AS
SELECT
  bc.book_id AS book_id,
  group_concat(coalesce(bc.name_override, c.name), ' / ') AS authors
FROM book_contributors bc
JOIN contributors c ON c.id = bc.contributor_id
WHERE bc.role = 'author'
GROUP BY bc.book_id;

CREATE TABLE tags (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  name_norm   TEXT NOT NULL,
  kind        TEXT NOT NULL DEFAULT 'tag' CHECK (kind IN ('tag', 'genre', 'subject')),
  color       TEXT,
  usage_count INTEGER NOT NULL DEFAULT 0 CHECK (usage_count >= 0),
  created_at  TEXT NOT NULL
) STRICT;

CREATE UNIQUE INDEX ux_tags_name_norm ON tags (name_norm);

CREATE TABLE book_tags (
  book_id    TEXT NOT NULL REFERENCES books (id) ON DELETE CASCADE,
  tag_id     TEXT NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
  added_by   TEXT REFERENCES users (id) ON DELETE SET NULL,
  added_at   TEXT NOT NULL,
  PRIMARY KEY (book_id, tag_id)
) STRICT;

CREATE INDEX ix_book_tags_tag ON book_tags (tag_id, book_id);

-- "分组": a named shelf of books. owner_user_id NULL means shared across all users.
-- Smart collections store a rule instead of membership and are evaluated server-side.
CREATE TABLE collections (
  id            TEXT PRIMARY KEY,
  owner_user_id TEXT REFERENCES users (id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  name_norm     TEXT NOT NULL,
  slug          TEXT,
  kind          TEXT NOT NULL DEFAULT 'collection' CHECK (kind IN ('collection', 'smart')),
  rule_json     TEXT CHECK (rule_json IS NULL OR json_valid(rule_json)),
  is_public     INTEGER NOT NULL DEFAULT 1 CHECK (is_public IN (0, 1)),
  position_mode TEXT NOT NULL DEFAULT 'manual' CHECK (position_mode IN ('manual', 'added', 'title')),
  description   TEXT,
  cover_book_id TEXT REFERENCES books (id) ON DELETE SET NULL,
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL,
  deleted_at    TEXT
) STRICT;

CREATE UNIQUE INDEX ux_collections_owner_name ON collections (ifnull(owner_user_id, ''), name_norm)
  WHERE deleted_at IS NULL;

CREATE TABLE collection_books (
  collection_id TEXT NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
  book_id       TEXT NOT NULL REFERENCES books (id) ON DELETE CASCADE,
  -- Manual ordering is only meaningful when position_mode = 'manual'; reordering rewrites
  -- positions in one transaction rather than maintaining a gap-filled float.
  position      INTEGER NOT NULL DEFAULT 0,
  note          TEXT,
  added_by      TEXT REFERENCES users (id) ON DELETE SET NULL,
  added_at      TEXT NOT NULL,
  PRIMARY KEY (collection_id, book_id)
) STRICT;

CREATE INDEX ix_collection_books_book ON collection_books (book_id);
CREATE INDEX ix_collection_books_pos ON collection_books (collection_id, position);
