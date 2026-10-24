import { readdirSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import type { Db } from './index.ts';
import { newId } from '../ids.ts';

/**
 * Forward-only SQL migrations, applied in filename order.
 *
 * The checksum is the safety property: if a migration that has already run on this database is
 * edited, we refuse to start rather than silently continuing with a schema that no longer
 * matches the code reading it. Fixing that is a deliberate `db:repair`, not an accident at boot.
 */

export class MigrationError extends Error {
  constructor(message: string, readonly details?: Record<string, unknown>) {
    super(message);
    this.name = 'MigrationError';
  }
}

interface MigrationFile {
  name: string;
  sql: string;
  checksum: string;
}

/**
 * CRLF-insensitive checksum. `.gitattributes` pins LF, but a checkout that ignored it (or a
 * Windows editor that renormalized on save) would otherwise look like tampering with applied
 * migrations and brick the server for a non-change.
 */
function fingerprint(sql: string): string {
  return createHash('sha256').update(sql.replace(/\r\n/g, '\n').trim()).digest('hex');
}

export function discoverMigrations(dir: string): MigrationFile[] {
  return readdirSync(dir)
    .filter((name) => /^\d{4}_.+\.sql$/.test(name))
    .sort()
    .map((name) => {
      const sql = readFileSync(join(dir, name), 'utf8');
      return { name, sql, checksum: fingerprint(sql) };
    });
}

function ensureMigrationsTable(db: Db): void {
  db.exec(`CREATE TABLE IF NOT EXISTS schema_migrations (
    name        TEXT PRIMARY KEY,
    checksum    TEXT NOT NULL,
    applied_at  TEXT NOT NULL,
    duration_ms INTEGER NOT NULL
  )`);
}

/** Applied migrations whose recorded checksum no longer matches the file on disk. */
export function findDrift(db: Db, dir: string): { name: string; expected: string; actual: string }[] {
  ensureMigrationsTable(db);
  const drift: { name: string; expected: string; actual: string }[] = [];
  for (const file of discoverMigrations(dir)) {
    const row = db.get<{ checksum: string }>(
      'SELECT checksum FROM schema_migrations WHERE name = ?',
      [file.name],
    );
    if (row && row.checksum !== file.checksum) {
      drift.push({ name: file.name, expected: row.checksum, actual: file.checksum });
    }
  }
  return drift;
}

export interface MigrationResult {
  applied: string[];
  alreadyPresent: number;
  schemaVersion: number;
  instanceId: string;
}

export function applyMigrations(db: Db, dir: string): MigrationResult {
  ensureMigrationsTable(db);

  const drift = findDrift(db, dir);
  if (drift.length > 0) {
    throw new MigrationError(
      `applied migrations were modified after the fact: ${drift.map((d) => d.name).join(', ')}. ` +
        'Add a new migration instead of editing one that has already run.',
      { drift },
    );
  }

  const applied: string[] = [];
  const files = discoverMigrations(dir);
  let alreadyPresent = 0;
  for (const file of files) {
    if (db.get('SELECT 1 FROM schema_migrations WHERE name = ?', [file.name])) {
      alreadyPresent += 1;
      continue;
    }
    const startedAt = performance.now();
    const now = new Date().toISOString();
    db.tx(() => {
      db.exec(file.sql);
      db.run('INSERT INTO schema_migrations (name, checksum, applied_at, duration_ms) VALUES (?, ?, ?, ?)', [
        file.name,
        file.checksum,
        now,
        0,
      ]);
    });
    // Written after the transaction commits, so the recorded duration includes the commit
    // itself — on a spinning-disk NAS that is the part worth knowing about.
    db.run('UPDATE schema_migrations SET duration_ms = ? WHERE name = ?', [
      Math.max(1, Math.round(performance.now() - startedAt)),
      file.name,
    ]);
    applied.push(file.name);
  }

  const schemaVersion = countApplied(db);
  const instanceId = ensureInstanceState(db);
  db.run('UPDATE app_state SET schema_version = ? WHERE id = 1', [schemaVersion]);

  return { applied, alreadyPresent, schemaVersion, instanceId };
}

function countApplied(db: Db): number {
  return db.get<{ n: number }>('SELECT count(*) AS n FROM schema_migrations')?.n ?? 0;
}

/**
 * Creates the singleton row and hands back the instance id. A different container that restored
 * the same volume would compute the same schema version but carry a different id, which is how
 * a client detects "this is not the server I cached against".
 */
function ensureInstanceState(db: Db): string {
  const existing = db.get<{ instance_id: string }>('SELECT instance_id FROM app_state WHERE id = 1');
  if (existing) return existing.instance_id;
  const id = newId();
  db.tx(() => {
    db.run(
      'INSERT INTO app_state (id, instance_id, schema_version, installed_at) VALUES (1, ?, 0, ?)',
      [id, new Date().toISOString()],
    );
  });
  return id;
}
