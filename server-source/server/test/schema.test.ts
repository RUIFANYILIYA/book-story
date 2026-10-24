import { beforeEach, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/index.ts';
import { ftsMatch, insertBook, insertUser, openMigratedDb } from './helpers/db.ts';

const ISO = '2026-01-01T00:00:00.000Z';
const SHA = (seed: string) => seed.padEnd(64, '0').slice(0, 64);

let db: Db;

beforeEach(() => {
  db = openMigratedDb();
});

describe('books_fts stays in step with books via triggers', () => {
  beforeEach(() => {
    insertBook(db, 'b1', { title: '三体死神永生', searchText: '三体死神永生 刘慈欣 科幻' });
    insertBook(db, 'b2', { title: 'Project Hail Mary', searchText: 'Project Hail Mary Andy Weir' });
  });

  it('indexes on insert and finds a 3+ character CJK substring', () => {
    expect(ftsMatch(db, '死神永')).toContain('b1');
  });

  it('finds an ASCII substring of 3+ characters', () => {
    expect(ftsMatch(db, 'Hail')).toContain('b2');
  });

  it('cannot answer a 2-character CJK query, which is why search routes short queries to LIKE', () => {
    // Whatever the tokenizer does with an under-length query — match nothing, or reject it —
    // it must not silently return a hit, and the LIKE fallback must cover that case.
    let ftsAnswered: string[] = [];
    try {
      ftsAnswered = ftsMatch(db, '三体');
    } catch {
      ftsAnswered = [];
    }
    expect(ftsAnswered).not.toContain('b1');
    const like = db
      .all<{ id: string }>("SELECT id FROM books WHERE search_text LIKE '%死神%'")
      .map((r) => r.id);
    expect(like).toContain('b1');
  });

  it('reflects an update rather than leaving a stale entry behind', () => {
    // `changes` is asserted because a mis-ordered argument list binds the WHERE value to the
    // wrong placeholder and no-ops silently — this exact test did that once.
    expect(
      db.run('UPDATE books SET title = ?, search_text = ? WHERE id = ?', ['renamed 标题', 'renamed 标题', 'b1'])
        .changes,
    ).toBe(1);
    expect(ftsMatch(db, 'renamed')).toContain('b1');
    // The old phrase must be gone from *every* indexed column, not just `title`: search_text is
    // also in books_fts, so leaving it stale keeps a deleted title findable.
    expect(ftsMatch(db, '死神永')).not.toContain('b1');
  });

  it('drops the row when the book is deleted', () => {
    db.run('DELETE FROM books WHERE id = ?', ['b2']);
    expect(ftsMatch(db, 'Hail')).not.toContain('b2');
  });
});

describe('STRICT tables', () => {
  it('reject a text value in a numeric column', () => {
    insertUser(db, 'u1');
    insertBook(db, 'b1');
    expect(() =>
      db.run(
        `INSERT INTO user_book_states (user_id, book_id, personal_rating, created_at, updated_at, rev)
         VALUES ('u1','b1','high',?,?,1)`,
        [ISO, ISO],
      ),
    ).toThrow(/cannot store TEXT value in INTEGER column/);
  });

  it('does NOT protect a TEXT column from a number, which arrives mangled beyond retrieval', () => {
    // Pinned because it contradicts the usual "STRICT will catch it" assumption. node:sqlite
    // binds a JS number as a double, so SQLite writes "123" into a STRICT TEXT column as the
    // string "123.0" — no error, and neither `id = '123'` nor `id = 123` finds it again.
    // The schema therefore cannot be the guard for id-shaped columns: `isId()` at the route
    // boundary has to be, and every id reaching here originates from a zod-validated string.
    expect(() => insertBook(db, 123 as unknown as string)).not.toThrow();
    expect(db.get<{ id: string }>("SELECT id FROM books WHERE id = '123.0'")?.id).toBe('123.0');
    expect(db.get("SELECT 1 FROM books WHERE id = '123'")).toBeUndefined();
    expect(db.get("SELECT 1 FROM books WHERE id = 123")).toBeUndefined();
  });

  it('widen an integer into a REAL column, so progress can be sent as 50 or 50.5', () => {
    insertUser(db, 'u1');
    insertBook(db, 'b1');
    db.run(
      `INSERT INTO user_book_states (user_id, book_id, progress_percent, created_at, updated_at, rev)
       VALUES ('u1', 'b1', 50, ?, ?, 1)`,
      [ISO, ISO],
    );
    expect(
      db.get<{ p: number }>('SELECT progress_percent AS p FROM user_book_states WHERE user_id = ?', ['u1'])?.p,
    ).toBe(50);
  });

  it('reject a boolean-ish column outside 0/1 and a JSON column that is not JSON', () => {
    insertUser(db, 'u1');
    insertBook(db, 'b1');
    expect(() =>
      db.run(
        `INSERT INTO user_book_states (user_id, book_id, shelf, created_at, updated_at, rev)
         VALUES ('u1','b1','limbo',?,?,1)`,
        [ISO, ISO],
      ),
    ).toThrow();
    expect(() =>
      db.run(
        `INSERT INTO user_book_states (user_id, book_id, position_json, created_at, updated_at, rev)
         VALUES ('u1','b1','not json',?,?,1)`,
        [ISO, ISO],
      ),
    ).toThrow();
  });
});

describe('uniqueness rules that encode product decisions', () => {
  it('treats a second identical live title as a duplicate but allows it once soft-deleted', () => {
    insertBook(db, 'b1', { title: '活着', sortKey: '余华 — 活着' });
    expect(() => insertBook(db, 'b2', { title: '活着', sortKey: '余华 — 活着' })).toThrow();

    db.run('UPDATE books SET deleted_at = ? WHERE id = ?', [ISO, 'b1']);
    insertBook(db, 'b2', { title: '活着', sortKey: '余华 — 活着' });
    expect(db.get("SELECT 1 FROM books WHERE id = 'b2'")).toBeDefined();
  });

  it('allows only one primary file per book', () => {
    insertBook(db, 'b1');
    db.run(
      `INSERT INTO blobs (id, sha256, size_bytes, storage_path, media_type, format, created_at)
       VALUES ('bl1', ?, 10, 'blobs/ab/cd/x.epub', 'application/epub+zip', 'epub', ?)`,
      [SHA('a'), ISO],
    );
    db.run(
      `INSERT INTO blobs (id, sha256, size_bytes, storage_path, media_type, format, created_at)
       VALUES ('bl2', ?, 10, 'blobs/ef/01/y.txt', 'text/plain', 'txt', ?)`,
      [SHA('b'), ISO],
    );
    const file = (id: string, blob: string, primary: number) =>
      db.run(
        `INSERT INTO book_files (id, book_id, blob_id, format, media_type, filename, size_bytes, sha256, is_primary, created_at, updated_at)
         VALUES (?, 'b1', ?, 'epub', 'application/epub+zip', ?, 10, ?, ?, ?, ?)`,
        [id, blob, `${id}.epub`, SHA(id), primary, ISO, ISO],
      );
    file('f1', 'bl1', 1);
    expect(() => file('f2', 'bl2', 1)).toThrow();
    file('f2', 'bl2', 0);
  });

  it('keeps a shared collection and a personal one of the same name apart', () => {
    insertUser(db, 'u1');
    const collection = (id: string, owner: string | null) =>
      db.run(
        `INSERT INTO collections (id, owner_user_id, name, name_norm, created_at, updated_at)
         VALUES (?, ?, '在读', '在读', ?, ?)`,
        [id, owner, ISO, ISO],
      );
    collection('c-shared', null);
    collection('c-u1', 'u1');
    expect(() => collection('c-u1-dup', 'u1')).toThrow();
  });

  it('refuses a second app_state row, which is what makes instance_id a singleton', () => {
    expect(db.get('SELECT count(*) AS n FROM app_state')?.n).toBe(1);
    expect(() =>
      db.run("INSERT INTO app_state (id, instance_id, schema_version, installed_at) VALUES (2, 'x', 1, ?)", [ISO]),
    ).toThrow();
  });
});

describe('author view', () => {
  it('joins contributors into the string the browse endpoints sort on', () => {
    insertBook(db, 'b1');
    db.run(
      `INSERT INTO contributors (id, name, name_norm, created_at, updated_at)
       VALUES ('cr1', '刘慈欣', '刘慈欣', ?, ?)`,
      [ISO, ISO],
    );
    db.run(
      `INSERT INTO contributors (id, name, name_norm, created_at, updated_at)
       VALUES ('cr2', 'Joe', 'joe', ?, ?)`,
      [ISO, ISO],
    );
    db.run("INSERT INTO book_contributors VALUES ('b1', 'cr1', 'author', 0, NULL)");
    db.run("INSERT INTO book_contributors VALUES ('b1', 'cr2', 'translator', 0, NULL)");
    db.run("INSERT INTO book_contributors VALUES ('b1', 'cr2', 'author', 1, NULL)");

    const row = db.get<{ authors: string }>('SELECT authors FROM v_book_authors WHERE book_id = ?', ['b1']);
    expect(row?.authors).toBe('刘慈欣 / Joe');
  });
});

describe('bookmark sync shape', () => {
  it('keeps one live bookmark per anchor but preserves the tombstone for delta sync', () => {
    insertUser(db, 'u1');
    insertBook(db, 'b1');
    const bm = (id: string, deleted: string | null) =>
      db.run(
        `INSERT INTO bookmarks (id, user_id, book_id, target_kind, target_value, created_at, updated_at, rev, deleted_at)
         VALUES (?, 'u1', 'b1', 'char', '42', ?, ?, 1, ?)`,
        [id, ISO, ISO, deleted],
      );
    bm('bm1', null);
    expect(() => bm('bm2', null)).toThrow();
    bm('bm2', ISO);
    expect(db.get("SELECT 1 FROM bookmarks WHERE id = 'bm2'")).toBeDefined();
  });
});
