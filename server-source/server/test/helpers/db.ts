import { fileURLToPath } from 'node:url';
import { openDb, type Db } from '../../src/db/index.ts';
import { applyMigrations } from '../../src/db/migrate.ts';

/** `server/migrations`, resolved from this file in both tsx and vitest. */
export const MIGRATIONS_DIR = fileURLToPath(new URL('../../migrations/', import.meta.url));

export function openMigratedDb(): Db {
  const db = openDb(':memory:');
  applyMigrations(db, MIGRATIONS_DIR);
  return db;
}

const ISO = '2026-01-01T00:00:00.000Z';

export function insertUser(
  db: Db,
  id: string,
  overrides: Partial<{ username: string; role: string; displayName: string }> = {},
): void {
  db.run(
    `INSERT INTO users (id, username, display_name, password_hash, role, created_at, updated_at)
     VALUES (?, ?, ?, 'x', ?, ?, ?)`,
    [id, overrides.username ?? `user_${id}`, overrides.displayName ?? '测试用户', overrides.role ?? 'user', ISO, ISO],
  );
}

export function insertBook(
  db: Db,
  id: string,
  overrides: Partial<{ title: string; sortKey: string; searchText: string; deletedAt: string | null }> = {},
): void {
  db.run(
    `INSERT INTO books (id, title, title_norm, sort_key, search_text, metadata_source,
                        content_updated_at, added_at, updated_at, deleted_at)
     VALUES (?, ?, ?, ?, ?, 'filename', ?, ?, ?, ?)`,
    [
      id,
      overrides.title ?? `书 ${id}`,
      (overrides.title ?? `书 ${id}`).toLowerCase(),
      overrides.sortKey ?? `作者 — ${id}`,
      overrides.searchText ?? `${overrides.title ?? id} 作者`,
      ISO,
      ISO,
      ISO,
      overrides.deletedAt ?? null,
    ],
  );
}

export function ftsMatch(db: Db, query: string): string[] {
  return db
    .all<{ book_id: string }>(
      `SELECT book_id FROM books_fts WHERE books_fts MATCH ? ORDER BY rank`,
      [`"${query.replaceAll('"', '""')}"`],
    )
    .map((r) => r.book_id);
}
