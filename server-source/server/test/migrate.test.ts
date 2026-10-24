import { cpSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { openDb } from '../src/db/index.ts';
import { applyMigrations, discoverMigrations, findDrift, MigrationError } from '../src/db/migrate.ts';
import { MIGRATIONS_DIR } from './helpers/db.ts';

function tempMigrations(): string {
  const dir = mkdtempSync(join(tmpdir(), 'ebookd-mig-'));
  // cpSync of a directory copies its *contents* into the target, so `dir` is the migrations dir.
  cpSync(MIGRATIONS_DIR, dir, { recursive: true });
  return dir;
}

describe('discoverMigrations', () => {
  it('reads the shipped migrations in filename order and ignores anything else', () => {
    const files = discoverMigrations(MIGRATIONS_DIR);
    expect(files.length).toBeGreaterThanOrEqual(6);
    expect(files.map((f) => f.name)).toEqual([...files.map((f) => f.name)].sort());
    expect(files[0]!.name).toBe('0001_identity.sql');
  });

  it('skips files that are not numbered migrations', () => {
    const dir = tempMigrations();
    writeFileSync(join(dir, 'notes.md'), '# not a migration');
    writeFileSync(join(dir, '9999_extra.sql'), 'SELECT 1;');
    const names = discoverMigrations(dir).map((f) => f.name);
    expect(names).not.toContain('notes.md');
    expect(names).toContain('9999_extra.sql');
  });
});

describe('applyMigrations', () => {
  it('installs the full schema exactly once', () => {
    // Derived from the files on disk so adding a migration updates the expectation instead of
    // turning a green suite red for a non-change.
    const expected = discoverMigrations(MIGRATIONS_DIR).length;
    const db = openDb(':memory:');
    const first = applyMigrations(db, MIGRATIONS_DIR);
    expect(first.applied).toHaveLength(expected);
    expect(first.schemaVersion).toBe(expected);
    expect(first.instanceId).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);

    const second = applyMigrations(db, MIGRATIONS_DIR);
    expect(second.applied).toEqual([]);
    expect(second.alreadyPresent).toBe(expected);
    expect(second.schemaVersion).toBe(expected);
    // Same database, same instance: a restart must not make every client cache look invalid.
    expect(second.instanceId).toBe(first.instanceId);
    db.close();
  });

  it('creates the tables the rest of the server assumes exist', () => {
    const db = openDb(':memory:');
    applyMigrations(db, MIGRATIONS_DIR);
    const tables = new Set(
      db
        .all<{ name: string }>("SELECT name FROM sqlite_master WHERE type IN ('table','view')")
        .map((r) => r.name),
    );
    for (const expected of [
      'users',
      'auth_devices',
      'blobs',
      'books',
      'book_files',
      'collections',
      'user_book_states',
      'reading_positions',
      'bookmarks',
      'upload_sessions',
      'ingest_jobs',
      'operation_logs',
      'app_state',
      'v_book_authors',
      'books_fts',
    ]) {
      expect(tables.has(expected), expected).toBe(true);
    }
    db.close();
  });

  it('refuses to start when an applied migration was edited afterwards', () => {
    const dir = tempMigrations();
    const db = openDb(':memory:');
    applyMigrations(db, dir);

    const target = join(dir, '0005_ops.sql');
    writeFileSync(target, `${readFileSync(target, 'utf8')}\n-- someone added a column by hand\n`);

    expect(findDrift(db, dir).map((d) => d.name)).toEqual(['0005_ops.sql']);
    expect(() => applyMigrations(db, dir)).toThrow(MigrationError);
    expect(() => applyMigrations(db, dir)).toThrow(/were modified after the fact/);
    db.close();
  });

  it('is not fooled by a CRLF checkout of an unchanged migration', () => {
    const dir = tempMigrations();
    const db = openDb(':memory:');
    applyMigrations(db, dir);

    const target = join(dir, '0005_ops.sql');
    const unix = readFileSync(target, 'utf8');
    writeFileSync(target, unix.replaceAll('\n', '\r\n'));
    expect(findDrift(db, dir)).toEqual([]);

    // A real change is still caught, so the leniency above cannot mask tampering.
    writeFileSync(target, `${unix}\nCREATE TABLE sneaky (id TEXT PRIMARY KEY);\n`);
    expect(findDrift(db, dir).map((d) => d.name)).toEqual(['0005_ops.sql']);
    db.close();
  });
});
