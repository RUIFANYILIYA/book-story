import { DatabaseSync } from 'node:sqlite';

export type SqlValue = string | number | bigint | null | Uint8Array;
export type PositionalParams = readonly SqlValue[];
export type NamedParams = Record<string, SqlValue>;

export interface RunResult {
  changes: number;
  lastInsertRowid: number;
}

/**
 * The single seam between the app and SQLite.
 *
 * Everything the rest of the codebase does with the database goes through these six methods,
 * for two reasons: swapping the driver stays a one-file change (see `open`), and every value
 * reaching a statement passes `normalize`, which is where the driver's binding rules stop
 * being somebody's tribal knowledge.
 *
 * BINDING IS ASYMMETRIC, AND THIS IS THE TRAP MOST LIKELY TO BITE LATER:
 *   * passing *more* values than the statement has placeholders throws;
 *   * passing *fewer* silently leaves the rest NULL.
 * So `UPDATE books SET title = ? WHERE id = ?` with the arguments the wrong way round does not
 * error — it matches nothing and reports `changes: 0`, looking exactly like "that row is
 * already gone". node:sqlite exposes no placeholder count to check against.
 * Therefore: any write whose WHERE clause must match must assert the returned `changes`, and
 * multi-column statements should prefer named parameters, where an unexpected key throws.
 */
export interface Db {
  all<T = Record<string, unknown>>(sql: string, params?: PositionalParams | NamedParams): T[];
  get<T = Record<string, unknown>>(sql: string, params?: PositionalParams | NamedParams): T | undefined;
  run(sql: string, params?: PositionalParams | NamedParams): RunResult;
  /** Multi-statement SQL with no parameters: DDL and PRAGMA. */
  exec(sql: string): void;
  /** Runs `fn` in a transaction; nested calls join via savepoints. */
  tx<T>(fn: () => T): T;
  close(): void;
}

/**
 * `node:sqlite` refuses to bind `boolean`, `undefined`, or a `Date`, and the repos would
 * otherwise crash on the first `isActive: true`. SQLite has no boolean type either, so this is
 * the one place those become 0/1, NULL and an ISO string.
 *
 * `bigint` is deliberately *not* narrowed to `number`: `INTEGER` columns are 64-bit, and a
 * 900 GB library blows a float's exact-integer range.
 *
 * What this cannot fix: a `number` aimed at a TEXT column. node:sqlite binds JS numbers as
 * doubles, and SQLite writes 123 into a TEXT column — even a STRICT one — as the string "123.0",
 * which then matches neither '123' nor 123. No error is raised, so ids must be validated as
 * strings at the route boundary (`isId`) rather than trusted to the schema.
 */
function normalize(value: unknown): SqlValue {
  if (value === undefined || value === null) return null;
  if (typeof value === 'boolean') return value ? 1 : 0;
  if (typeof value === 'number' || typeof value === 'bigint' || typeof value === 'string') {
    return value;
  }
  if (value instanceof Date) return value.toISOString();
  if (ArrayBuffer.isView(value)) return value as Uint8Array;
  throw new TypeError(
    `cannot bind ${Object.prototype.toString.call(value)} as a SQL parameter; convert it at the call site`,
  );
}

function normalizeParams(
  params: PositionalParams | NamedParams | undefined,
): SqlValue[] | NamedParams | undefined {
  if (params === undefined) return undefined;
  if (Array.isArray(params)) return (params as readonly unknown[]).map(normalize);
  const out: NamedParams = {};
  for (const [key, value] of Object.entries(params)) out[key] = normalize(value);
  return out;
}

/** Pragmatic view of `StatementSync`: rest-argument binding with normalized values. */
interface LooseStatement {
  all(...args: unknown[]): unknown;
  get(...args: unknown[]): unknown;
  run(...args: unknown[]): { changes: number | bigint; lastInsertRowid: number | bigint };
}

/**
 * Pragmas that must be set per connection.
 *
 * `foreign_keys` is per-connection in SQLite, not persisted in the file, so it belongs here and
 * nowhere else. WAL is persistent but re-asserting it is free and makes the log legible.
 */
function applyPragmas(db: DatabaseSync, path: string): void {
  db.exec('PRAGMA busy_timeout = 5000');
  db.exec('PRAGMA foreign_keys = ON');
  db.exec('PRAGMA synchronous = NORMAL');
  db.exec('PRAGMA temp_store = MEMORY');
  db.exec('PRAGMA cache_size = -32000');
  db.exec('PRAGMA wal_autocheckpoint = 1000');
  // mmap_size stays 0 on purpose: shared memory mappings are unreliable on 9p/virtiofs, which
  // is exactly how a Windows host reaches a WSL-mounted path.
  db.exec('PRAGMA mmap_size = 0');
  if (path !== ':memory:') {
    // A file database wants concurrent readers alongside the ingest writer.
    db.exec('PRAGMA journal_mode = WAL');
  }
}

export class SqliteDb implements Db {
  private depth = 0;
  private savepointCounter = 0;

  private constructor(readonly raw: DatabaseSync) {}

  static open(path: string): SqliteDb {
    const raw = new DatabaseSync(path);
    applyPragmas(raw, path);
    return new SqliteDb(raw);
  }

  /**
   * `node:sqlite` takes anonymous parameters as *rest arguments*, not as an array — handing it
   * an array makes it treat the indices as named parameters and fail with
   * "Unknown named parameter '0'". The typed signature is also awkwardly narrow for a
   * normalized value union, so one documented cast here keeps three call sites clean.
   */
  private stmt(sql: string): LooseStatement {
    return this.raw.prepare(sql) as unknown as LooseStatement;
  }

  private args(params: PositionalParams | NamedParams | undefined): unknown[] {
    const p = normalizeParams(params);
    if (p === undefined) return [];
    return Array.isArray(p) ? [...p] : [p];
  }

  all<T = Record<string, unknown>>(sql: string, params?: PositionalParams | NamedParams): T[] {
    return this.stmt(sql).all(...this.args(params)) as T[];
  }

  get<T = Record<string, unknown>>(sql: string, params?: PositionalParams | NamedParams): T | undefined {
    return this.stmt(sql).get(...this.args(params)) as T | undefined;
  }

  run(sql: string, params?: PositionalParams | NamedParams): RunResult {
    const result = this.stmt(sql).run(...this.args(params));
    return {
      changes: Number(result.changes),
      lastInsertRowid: Number(result.lastInsertRowid),
    };
  }

  exec(sql: string): void {
    this.raw.exec(sql);
  }

  /**
   * `BEGIN IMMEDIATE` takes the write lock at once instead of upgrading mid-transaction, which
   * is the difference between a retry and an SQLITE_BUSY that can only be resolved by rolling
   * the whole unit of work back.
   */
  tx<T>(fn: () => T): T {
    if (this.depth > 0) {
      const name = `sp_${(this.savepointCounter += 1)}`;
      this.depth += 1;
      this.raw.exec(`SAVEPOINT ${name}`);
      try {
        const out = fn();
        this.raw.exec(`RELEASE ${name}`);
        return out;
      } catch (err) {
        this.raw.exec(`ROLLBACK TO ${name}`);
        this.raw.exec(`RELEASE ${name}`);
        throw err;
      } finally {
        this.depth -= 1;
      }
    }

    this.depth += 1;
    this.raw.exec('BEGIN IMMEDIATE');
    try {
      const out = fn();
      this.raw.exec('COMMIT');
      return out;
    } catch (err) {
      try {
        this.raw.exec('ROLLBACK');
      } catch {
        // The transaction was already aborted by SQLite; the original error is the useful one.
      }
      throw err;
    } finally {
      this.depth -= 1;
    }
  }

  close(): void {
    this.raw.exec('PRAGMA optimize');
    this.raw.close();
  }
}

export function openDb(path: string): Db {
  return SqliteDb.open(path);
}

/**
 * Driver errors arrive as a plain Error whose `code` is always `ERR_SQLITE_ERROR` and whose
 * `errstr` is always `constraint failed` — neither distinguishes anything. The two fields that
 * do are the numeric `errcode` (SQLite's extended result code) and `message`, which carries
 * SQLite's own stable wording. Verified against this runtime, not from documentation memory:
 *   787  SQLITE_CONSTRAINT_FOREIGNKEY   "FOREIGN KEY constraint failed"
 *   1299 SQLITE_CONSTRAINT_UNIQUE       "UNIQUE constraint failed: t.col"
 *   1555 SQLITE_CONSTRAINT_PRIMARYKEY   "UNIQUE constraint failed: t.id"  (same wording as above)
 *   3091 STRICT type mismatch           "cannot store TEXT value in INTEGER column t.col"
 * Because a PRIMARY KEY collision reports with the UNIQUE wording, unique-style conflicts are
 * matched on the message and both codes, which is what the repositories actually need.
 */
const ERR_FOREIGN_KEY = 787;
const ERR_UNIQUE = 1299;
const ERR_PRIMARY_KEY = 1555;

function errcodeOf(err: unknown): number | undefined {
  const v = (err as { errcode?: unknown }).errcode;
  return typeof v === 'number' ? v : undefined;
}

function messageOf(err: unknown): string {
  const e = err as { message?: unknown; errstr?: unknown };
  return [e.message, e.errstr].filter((s) => typeof s === 'string').join(' ');
}

export function isUniqueViolation(err: unknown): boolean {
  const code = errcodeOf(err);
  if (code === ERR_UNIQUE || code === ERR_PRIMARY_KEY) return true;
  return /UNIQUE constraint failed/i.test(messageOf(err));
}

export function isForeignKeyViolation(err: unknown): boolean {
  if (errcodeOf(err) === ERR_FOREIGN_KEY) return true;
  return /FOREIGN KEY constraint failed/i.test(messageOf(err));
}

/** A STRICT table rejected a value's type — always a programming error, never user input. */
export function isTypeMismatch(err: unknown): boolean {
  return /cannot store \w+ value in \w+ column/i.test(messageOf(err));
}

export function sqliteErrorCode(err: unknown): string | undefined {
  const e = err as { code?: unknown };
  return typeof e?.code === 'string' ? e.code : undefined;
}
