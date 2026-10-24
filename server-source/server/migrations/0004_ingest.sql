-- 0004_ingest — the receipt and work queue behind "put a file on the server".
--
-- Upload is plain multipart streamed to a temp file, not a resumable protocol: the resumable
-- server libraries on npm are unmaintained, ebooks are dominated by small EPUB/TXT, and
-- dedupe-by-sha256 plus an idempotency key already makes a retried 300 MB POST cost nothing
-- extra. The received_size/tmp_path columns below are what a chunked mode would hang off later,
-- so adding it stays additive rather than a rewrite.

CREATE TABLE upload_sessions (
  id              TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  filename        TEXT NOT NULL,
  declared_size   INTEGER CHECK (declared_size IS NULL OR declared_size >= 0),
  received_size   INTEGER NOT NULL DEFAULT 0 CHECK (received_size >= 0),
  sha256          TEXT CHECK (sha256 IS NULL OR length(sha256) = 64),
  tmp_path        TEXT NOT NULL,
  -- auto: attach to a title-matched book, else create one. create/attach/replace are explicit.
  mode            TEXT NOT NULL DEFAULT 'auto' CHECK (mode IN ('auto', 'create', 'attach', 'replace')),
  target_book_id  TEXT REFERENCES books (id) ON DELETE SET NULL,
  options_json    TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(options_json)),
  -- Retrying the same upload after a dropped connection must not create two books.
  idempotency_key TEXT,
  status          TEXT NOT NULL CHECK (status IN ('receiving', 'received', 'processing', 'committed', 'failed', 'expired')),
  error_code      TEXT,
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL,
  expires_at      TEXT NOT NULL,
  UNIQUE (user_id, idempotency_key)
) STRICT;

CREATE INDEX ix_upload_sessions_status ON upload_sessions (status, updated_at);
CREATE INDEX ix_upload_sessions_user ON upload_sessions (user_id, created_at DESC);

CREATE TABLE ingest_jobs (
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL CHECK (kind IN ('ingest_file', 'reparse', 'cover', 'bulk_import', 'fts_rebuild', 'gc')),
  upload_id    TEXT REFERENCES upload_sessions (id) ON DELETE SET NULL,
  book_id      TEXT REFERENCES books (id) ON DELETE CASCADE,
  file_id      TEXT REFERENCES book_files (id) ON DELETE SET NULL,
  blob_id      TEXT REFERENCES blobs (id) ON DELETE SET NULL,
  user_id      TEXT REFERENCES users (id) ON DELETE SET NULL,
  state        TEXT NOT NULL CHECK (state IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
  attempts     INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  max_attempts INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts >= 1),
  progress     REAL NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 1),
  -- 1 when the content hash already existed; the book is attached, no bytes written.
  deduped      INTEGER CHECK (deduped IS NULL OR deduped IN (0, 1)),
  dry_run      INTEGER NOT NULL DEFAULT 0 CHECK (dry_run IN (0, 1)),
  result_json  TEXT CHECK (result_json IS NULL OR json_valid(result_json)),
  error_code   TEXT,
  error_message TEXT,
  created_at   TEXT NOT NULL,
  started_at   TEXT,
  finished_at  TEXT
) STRICT;

CREATE INDEX ix_ingest_jobs_state ON ingest_jobs (state, created_at);
CREATE INDEX ix_ingest_jobs_book ON ingest_jobs (book_id, created_at DESC);
CREATE UNIQUE INDEX ux_ingest_jobs_upload ON ingest_jobs (upload_id) WHERE upload_id IS NOT NULL;
