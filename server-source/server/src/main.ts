import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { loadConfig, type Config } from './config.ts';
import { buildApp } from './app.ts';
import { openDb } from './db/index.ts';
import { applyMigrations } from './db/migrate.ts';
import { ensureBootstrapAdmin } from './auth/bootstrap.ts';
import { startSweeper } from './maintenance/sweeper.ts';
import { startLinkWatcher } from './library/link-watcher.ts';
import { SERVER_VERSION } from './version.ts';

/** Sibling of both `src/` and `dist/`, so the same expression works under tsx and in the image. */
const MIGRATIONS_DIR = fileURLToPath(new URL('../migrations/', import.meta.url));

/**
 * The container image pre-creates and chowns these; a bare `npm run start` on a NAS host does
 * not, and SQLite then fails with an opaque SQLITE_CANTOPEN. Creating them up front turns that
 * into a permission error that names the exact directory.
 */
function ensureStorageDirs(config: Config): void {
  mkdirSync(config.dataDir, { recursive: true });
  for (const directory of Object.values(config.paths)) {
    mkdirSync(directory, { recursive: true });
  }
}

async function main(): Promise<void> {
  const config = loadConfig();
  ensureStorageDirs(config);

  const db = openDb(config.dbFile);
  const migration = applyMigrations(db, MIGRATIONS_DIR);

  const app = await buildApp({ config, db, instanceId: migration.instanceId });

  // After buildApp so the generated password goes through the configured logger rather than a
  // stray console.write, and before listen so no request can arrive in the gap.
  await ensureBootstrapAdmin(db, config, app.log);

  await app.listen({ host: config.host, port: config.port });
  app.log.info(
    {
      version: SERVER_VERSION,
      env: config.env,
      instanceId: migration.instanceId,
      schemaVersion: migration.schemaVersion,
      migrationsApplied: migration.applied.length,
      dataDir: config.dataDir,
      libraryDir: config.libraryDir,
    },
    'ebookd listening',
  );

  // Started after `listen` so the first sweep's delay overlaps serving traffic rather than the
  // migration that has to finish before anything can be answered.
  const stopSweeper = startSweeper({ db, config, logger: app.log });

  // Only when EBOOK_WATCH_DIR is set. Its files are catalogued in place, so a scan can never move
  // or delete anything the user put there — the folder is expected to be mounted read-only.
  const watcher = startLinkWatcher({ db, config, logger: app.log });

  let closing = false;
  const shutdown = (signal: string): void => {
    if (closing) return;
    closing = true;
    app.log.info({ signal }, 'shutting down');
    stopSweeper();
    watcher.stop();
    app
      .close()
      .then(() => {
        // close() runs PRAGMA optimize then checkpoints and closes the handle; skipping it
        // leaves a -wal file that the next start must replay.
        db.close();
        process.exit(0);
      })
      .catch((err: unknown) => {
        app.log.error({ err }, 'unclean shutdown');
        process.exit(1);
      });
  };
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

main().catch((err: unknown) => {
  process.stderr.write(`${err instanceof Error ? err.message : String(err)}\n`);
  process.exit(1);
});
