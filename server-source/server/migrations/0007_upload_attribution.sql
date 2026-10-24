-- 0007_upload_attribution — make an upload attributable to whoever performed it.
--
-- The library itself is shared by design, so "bytes used by this user" has to mean "bytes this
-- user brought in", not "bytes this user can see". Without a writer column on `books`, a quota is
-- unenforceable and per-user storage reporting would have to count other people's uploads as this
-- person's — a number that looks plausible and is wrong.
--
-- ON DELETE SET NULL rather than CASCADE: deleting an account must not delete the books it
-- uploaded (see the DELETE /users/:id rule), and a book whose uploader is gone stays exactly as
-- readable as before. It simply stops counting toward anyone's quota.

ALTER TABLE books ADD COLUMN added_by TEXT REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE books ADD COLUMN added_from TEXT REFERENCES upload_sessions(id) ON DELETE SET NULL;

CREATE INDEX ix_books_added_by ON books (added_by) WHERE deleted_at IS NULL;
