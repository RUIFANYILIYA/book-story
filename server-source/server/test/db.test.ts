import { beforeEach, describe, expect, it } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { isForeignKeyViolation, isUniqueViolation, openDb, type Db, type PositionalParams } from '../src/db/index.ts';

let db: Db;

beforeEach(() => {
  db = openDb(':memory:');
  db.exec(`CREATE TABLE probe (
    id     TEXT PRIMARY KEY,
    n      INTEGER NOT NULL,
    flag   INTEGER NOT NULL CHECK (flag IN (0,1)),
    maybe  TEXT,
    at     TEXT NOT NULL
  )`);
});

describe('parameter binding', () => {
  // Deliberately `unknown[]`: these tests exist to pass values the *public* signature forbids,
  // so the cast is the point rather than a way to satisfy the compiler.
  const insert = (params: readonly unknown[]) =>
    db.run(
      'INSERT INTO probe (id, n, flag, maybe, at) VALUES (?, ?, ?, ?, ?)',
      params as unknown as PositionalParams,
    );

  it('narrows booleans to the 0/1 that SQLite expects', () => {
    insert(['a', 1, true, null, '2026-01-01T00:00:00.000Z']);
    expect(db.get<{ flag: number }>('SELECT flag FROM probe WHERE id = ?', ['a'])?.flag).toBe(1);
    insert(['b', 1, false, null, '2026-01-01T00:00:00.000Z']);
    expect(db.get<{ flag: number }>('SELECT flag FROM probe WHERE id = ?', ['b'])?.flag).toBe(0);
  });

  it('turns undefined into NULL so an omitted optional field does not crash the statement', () => {
    insert(['c', 1, 1, undefined, '2026-01-01T00:00:00.000Z']);
    const row = db.get<{ maybe: string | null }>('SELECT maybe FROM probe WHERE id = ?', ['c']);
    expect(row?.maybe).toBeNull();
  });

  it('serializes a Date to the ISO text the column is declared as', () => {
    insert(['d', 1, 1, null, new Date(Date.UTC(2026, 4, 17))]);
    expect(db.get<{ at: string }>('SELECT at FROM probe WHERE id = ?', ['d'])?.at).toBe(
      '2026-05-17T00:00:00.000Z',
    );
  });

  it('refuses an object with a message that names the problem, before SQLite sees it', () => {
    expect(() => insert(['e', 1, 1, { nested: true }, 'x'])).toThrow(TypeError);
    expect(() => insert(['e', 1, 1, { nested: true }, 'x'])).toThrow(/cannot bind/);
  });

  it('supports named parameters', () => {
    db.run('INSERT INTO probe (id, n, flag, maybe, at) VALUES ($id, $n, $flag, $maybe, $at)', {
      id: 'f',
      n: 2,
      flag: 1,
      maybe: null,
      at: '2026-01-01T00:00:00.000Z',
    });
    expect(db.get('SELECT id FROM probe WHERE id = ?', ['f'])).toBeDefined();
  });
});

describe('transactions', () => {
  beforeEach(() => {
    db.run("INSERT INTO probe VALUES ('keep', 1, 1, NULL, '2026-01-01T00:00:00.000Z')");
  });

  it('rolls the whole unit of work back when the body throws', () => {
    expect(() =>
      db.tx(() => {
        db.run("INSERT INTO probe VALUES ('gone', 1, 1, NULL, '2026-01-01T00:00:00.000Z')");
        throw new Error('boom');
      }),
    ).toThrow('boom');
    expect(db.get("SELECT 1 FROM probe WHERE id = 'gone'")).toBeUndefined();
    expect(db.get("SELECT 1 FROM probe WHERE id = 'keep'")).toBeDefined();
  });

  it('commits what the body did when it returns', () => {
    const out = db.tx(() => {
      db.run("INSERT INTO probe VALUES ('made', 1, 1, NULL, '2026-01-01T00:00:00.000Z')");
      return 42;
    });
    expect(out).toBe(42);
    expect(db.get("SELECT 1 FROM probe WHERE id = 'made'")).toBeDefined();
  });

  it('lets an inner failure roll back to a savepoint without poisoning the outer transaction', () => {
    db.tx(() => {
      db.run("INSERT INTO probe VALUES ('outer', 1, 1, NULL, '2026-01-01T00:00:00.000Z')");
      expect(() =>
        db.tx(() => {
          db.run("INSERT INTO probe VALUES ('inner', 1, 1, NULL, '2026-01-01T00:00:00.000Z')");
          throw new Error('inner boom');
        }),
      ).toThrow('inner boom');
      db.run("INSERT INTO probe VALUES ('after', 1, 1, NULL, '2026-01-01T00:00:00.000Z')");
    });
    expect(db.get("SELECT 1 FROM probe WHERE id = 'outer'")).toBeDefined();
    expect(db.get("SELECT 1 FROM probe WHERE id = 'after'")).toBeDefined();
    expect(db.get("SELECT 1 FROM probe WHERE id = 'inner'")).toBeUndefined();
  });

  it('reports affected rows', () => {
    const result = db.run("UPDATE probe SET n = 9 WHERE id IN ('keep')");
    expect(result.changes).toBe(1);
  });
});

describe('the binding asymmetry every repository must survive', () => {
  beforeEach(() => {
    db.run("INSERT INTO probe VALUES ('k', 1, 1, NULL, '2026-01-01T00:00:00.000Z')");
  });

  it('throws when more values than placeholders are supplied', () => {
    expect(() => db.run('UPDATE probe SET n = ? WHERE id = ?', [5, 'k', 'extra'])).toThrow();
  });

  it('silently binds NULL when fewer values are supplied, so writes must assert changes', () => {
    // The dangerous direction: `WHERE id = ?` receives no value, compares against NULL, matches
    // nothing, and looks identical to "already deleted". node:sqlite offers no placeholder count
    // to check, which is why the facade documents asserting `changes`.
    const result = db.run('UPDATE probe SET n = ? WHERE id = ?', [5]);
    expect(result.changes).toBe(0);
    expect(db.get<{ n: number }>("SELECT n FROM probe WHERE id = 'k'")?.n).toBe(1);
  });
});

describe('integrity', () => {
  it('enforces foreign keys, which are OFF per connection unless every open sets them', () => {
    db.exec('CREATE TABLE parent (id TEXT PRIMARY KEY)');
    db.exec('CREATE TABLE child (id TEXT PRIMARY KEY, parent_id TEXT REFERENCES parent(id))');
    expect(() => db.run("INSERT INTO child VALUES ('c', 'missing')")).toThrow();
    try {
      db.run("INSERT INTO child VALUES ('c', 'missing')");
    } catch (err) {
      expect(isForeignKeyViolation(err)).toBe(true);
    }
    db.run("INSERT INTO parent VALUES ('p')");
    db.run("INSERT INTO child VALUES ('c2', 'p')");
    expect(db.get("SELECT 1 FROM child WHERE id = 'c2'")).toBeDefined();
  });

  it('classifies a unique violation separately from a typo in the SQL', () => {
    db.run("INSERT INTO probe VALUES ('u', 1, 1, NULL, '2026-01-01T00:00:00.000Z')");
    try {
      db.run("INSERT INTO probe VALUES ('u', 1, 1, NULL, '2026-01-01T00:00:00.000Z')");
      expect.unreachable('duplicate primary key should have thrown');
    } catch (err) {
      expect(isUniqueViolation(err)).toBe(true);
      expect(isForeignKeyViolation(err)).toBe(false);
    }
  });
});

describe('connection pragmas', () => {
  it('uses WAL for a file database so the ingest writer never blocks a reader', () => {
    const dir = mkdtempSync(join(tmpdir(), 'ebookd-db-'));
    const file = openDb(join(dir, 't.sqlite3'));
    try {
      expect(file.get<{ journal_mode: string }>('PRAGMA journal_mode')?.journal_mode).toBe('wal');
      expect(file.get<{ foreign_keys: number }>('PRAGMA foreign_keys')?.foreign_keys).toBe(1);
      expect(file.get<{ timeout: number }>('PRAGMA busy_timeout')?.timeout).toBeGreaterThanOrEqual(5000);
    } finally {
      file.close();
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
