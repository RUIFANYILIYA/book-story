-- 0006_search — full-text index over the *metadata* columns derivable from `books` alone.
--
-- Two deliberate constraints shape this file:
--
-- 1. Only columns that live on `books` are indexed, so plain triggers can keep the index in
--    step inside the same transaction. `authors` and `tags` come from joined tables, so the
--    ingest layer folds them into `books.search_text` (a normalized concatenation) and that
--    column carries them into the index. A trigger cannot run a join per row without turning
--    every metadata edit into a table scan.
--
-- 2. The tokenizer is `trigram`, because this build of SQLite has no ICU tokenizer and
--    unicode61 does not segment Chinese at all. trigram indexes every 3-byte/3-char window, so
--    it answers substring queries in CJK — but only for queries of 3+ characters. The
--    repository routes 1-2 character queries to LIKE on `search_text` instead. That routing is
--    asserted in test/schema.test.ts rather than assumed, because the whole search UX depends
--    on which side of that boundary a query falls.
--
-- `content=` is intentionally not set: an external-content FTS table would have to agree with
-- `books` row for row, and `books` uses a TEXT primary key whose rowid churns on vacuum, which
-- is exactly the kind of coupling that silently corrupts an index.

CREATE VIRTUAL TABLE books_fts USING fts5(
  book_id UNINDEXED,
  title,
  sort_key,
  publisher,
  description,
  search_text,
  tokenize = 'trigram case_sensitive 0'
);

CREATE TRIGGER books_fts_ai AFTER INSERT ON books
BEGIN
  INSERT INTO books_fts (rowid, book_id, title, sort_key, publisher, description, search_text)
  VALUES (
    new.rowid,
    new.id,
    new.title,
    new.sort_key,
    coalesce(new.publisher, ''),
    coalesce(new.description, ''),
    new.search_text
  );
END;

CREATE TRIGGER books_fts_ad AFTER DELETE ON books
BEGIN
  DELETE FROM books_fts WHERE rowid = old.rowid;
END;

-- Indexing the whole row on any update is cheaper and far harder to get wrong than enumerating
-- which of the six indexed columns changed; metadata edits are not a hot path.
CREATE TRIGGER books_fts_au AFTER UPDATE ON books
BEGIN
  DELETE FROM books_fts WHERE rowid = old.rowid;
  INSERT INTO books_fts (rowid, book_id, title, sort_key, publisher, description, search_text)
  VALUES (
    new.rowid,
    new.id,
    new.title,
    new.sort_key,
    coalesce(new.publisher, ''),
    coalesce(new.description, ''),
    new.search_text
  );
END;
