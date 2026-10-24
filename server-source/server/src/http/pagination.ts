import { createHmac, timingSafeEqual } from 'node:crypto';
import { AppError } from '../errors.ts';

/**
 * Opaque, signed keyset cursors.
 *
 * Offset pagination is the wrong tool here for two reasons specific to a library: a page that
 * shifts because someone else uploaded a book silently shows or hides a row, and OFFSET into a
 * sorted 50k-row scan gets slower with every page. Keyset pagination fixes both — but it needs
 * the current sort values echoed back, and those are user-visible data.
 *
 * Signing is not about preventing cheating (a client that skips ahead gains nothing it could not
 * get by browsing). It is so a malformed or invented cursor is rejected as `bad_request` with a
 * clean 400 instead of being spliced into a WHERE clause and surfacing as a 500.
 */

/**
 * Sort key → the SQL columns it orders by, tiebroken on `b.id` so keyset paging is total.
 *
 * The aliases have to match what the consuming query actually joins: `b` is books, `s` is
 * **series**, `ub` is `user_book_states`, and `cb` is `collection_books` — which only exists while a
 * collection filter joins it. A column from a table that was never joined is a 500, not an empty
 * page, so `shelf` is rejected at the route unless a collection was named.
 */
export const SORT_KEYS = {
  addedAt: ['b.added_at', 'b.id'],
  title: ['b.sort_title', 'b.id'],
  author: ['b.sort_key', 'b.id'],
  year: ['b.published_year', 'b.id'],
  lastRead: ['ub.last_read_at', 'b.id'],
  progress: ['ub.progress_percent', 'b.id'],
  size: ['b.storage_bytes', 'b.id'],
  shelf: ['cb.position', 'b.id'],
} as const;

export type SortKey = keyof typeof SORT_KEYS;

export interface Cursor {
  sort: SortKey;
  order: 'asc' | 'desc';
  /** The tiebreak column values from the last row of the previous page. */
  after: (string | number | null)[];
}

export class CursorCodec {
  readonly #key: Buffer;

  constructor(secret: string) {
    this.#key = Buffer.from(secret, 'utf8');
  }

  encode(cursor: Cursor): string {
    const payload = Buffer.from(JSON.stringify(cursor), 'utf8');
    const mac = createHmac('sha256', this.#key).update(payload).digest().subarray(0, 12);
    return `${payload.toString('base64url')}.${mac.toString('base64url')}`;
  }

  decode(token: string): Cursor | null {
    const [body, signature] = token.split('.');
    if (!body || !signature) return null;
    let payload: Buffer;
    let provided: Buffer;
    try {
      payload = Buffer.from(body, 'base64url');
      provided = Buffer.from(signature, 'base64url');
    } catch {
      return null;
    }
    const expected = createHmac('sha256', this.#key).update(payload).digest().subarray(0, 12);
    if (provided.length !== expected.length || !timingSafeEqual(provided, expected)) return null;

    try {
      const parsed = JSON.parse(payload.toString('utf8')) as unknown;
      return isCursor(parsed) ? parsed : null;
    } catch {
      return null;
    }
  }
}

function isCursor(value: unknown): value is Cursor {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.sort === 'string' &&
    Object.hasOwn(SORT_KEYS, v.sort) &&
    (v.order === 'asc' || v.order === 'desc') &&
    Array.isArray(v.after)
  );
}

export interface PageParams {
  limit: number;
  cursor?: string | null;
  sort: SortKey;
  order: 'asc' | 'desc';
}

export interface Paged<T> {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
}

/**
 * Build the `WHERE` fragment for the next page.
 *
 * The comparison is lexicographic on the tuple, not "less than the last value", because a
 * descending sort over a nullable column would otherwise drop every row with NULL: `NULL < x` is
 * unknown in SQL, not false. The tiebreak on the id makes the ordering total, so a page boundary
 * can never duplicate or skip a row when two books share a sort value.
 */
export function keysetCondition(
  codec: CursorCodec,
  cursorToken: string | null | undefined,
  sort: SortKey,
  order: 'asc' | 'desc',
): { sql: string; params: (string | number)[] } {
  if (!cursorToken) return { sql: '', params: [] };
  const cursor = codec.decode(cursorToken);
  if (!cursor) throw new AppError('bad_request', 'cursor is not valid for this query');
  if (cursor.sort !== sort || cursor.order !== order) {
    throw new AppError('bad_request', 'cursor was issued for a different sort or order');
  }
  const columns = SORT_KEYS[sort];
  if (cursor.after.length !== columns.length) {
    throw new AppError('bad_request', 'cursor does not match the requested sort');
  }

  const operator = order === 'asc' ? '>' : '<';
  // Each clause re-states every preceding column (`a > ? OR (a = ? AND b > ?)`), so a preceding
  // value is bound once per clause that mentions it. Deriving the parameters from the same loop
  // that emits the SQL is what keeps the two in step; mapping over `after` alone produces one
  // value per column and silently leaves the last placeholder NULL, which matches no row -
  // turning page 2 into an empty result rather than an error.
  const clauses: string[] = [];
  const params: (string | number)[] = [];
  for (let i = 0; i < columns.length; i += 1) {
    const prefix: string[] = [];
    for (let p = 0; p < i; p += 1) {
      prefix.push(`${columns[p]} = ?`);
      params.push(bindable(cursor.after[p]));
    }
    clauses.push(prefix.length > 0 ? `(${prefix.join(' AND ')} AND ${columns[i]} ${operator} ?)` : `${columns[i]} ${operator} ?`);
    params.push(bindable(cursor.after[i]));
  }
  return { sql: ` AND (${clauses.join(' OR ')})`, params };
}

function bindable(value: string | number | null | undefined): string | number {
  return typeof value === 'number' ? value : String(value ?? '');
}

/** The values the next cursor needs, read off the last row of this page. */
export function tailValues<T extends Record<string, unknown>>(
  rows: T[],
  sort: SortKey,
): (string | number | null)[] {
  const last = rows[rows.length - 1];
  if (!last) return [];
  const columns = SORT_KEYS[sort];
  // The SQL layer aliases every keyset column to k0..kn precisely so this lookup cannot pick a
  // different column than the one the ORDER BY used.
  return columns.map((_column, index) => {
    const alias = `k${index}`;
    const value = last[alias];
    return typeof value === 'number' || typeof value === 'string' || value === null ? value : null;
  });
}

export function limitOf(requested: number | undefined, fallback = 50, max = 200): number {
  if (requested === undefined) return fallback;
  return Math.max(1, Math.min(max, Math.trunc(requested)));
}
