-- 0010_external_blobs — a book whose bytes stay where the user put them.
--
-- The library is content-addressed: ingest hashes the file, moves it into blobs/ and records a path
-- relative to the library. That is the right model for uploads, but it is the wrong one for a
-- watched folder of ten thousand books — cataloguing them would mean a second copy of everything.
--
-- So a blob may now be *external*: `storage_path` is an absolute path outside the library, and the
-- bytes belong to the user. Two rules follow and both are enforced in code, not here: the sweeper
-- never retires, reclaims or trashes an external blob (it would be deleting a file the library does
-- not own), and deleting the book leaves the file alone.
ALTER TABLE blobs ADD COLUMN external INTEGER NOT NULL DEFAULT 0;

-- The watcher asks "have I already linked this path?" on every scan; without this it is a table
-- scan per file.
CREATE INDEX IF NOT EXISTS ix_blobs_storage_path ON blobs (storage_path);
