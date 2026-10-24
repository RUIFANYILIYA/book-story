import { readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import type { Config } from '../config.ts';
import type { Db } from '../db/index.ts';
import { AppError } from '../errors.ts';
import { BlobRepo } from '../db/repositories/blob.repo.ts';
import { EBOOK_EXTENSIONS, ingestFile } from './ingest.ts';

/**
 * A folder watched for books, catalogued where they lie.
 *
 * This exists for the NAS case the rest of the design is built around: the books are already on
 * the share, often tens of thousands of them, and copying them into the library would double the
 * space for no gain. So the watcher registers each file *in place* — the library records a path,
 * the sweeper is told never to touch it, and deleting a book leaves the file alone.
 *
 * A poll, not inotify, and deliberately: the folder is usually a mount, and `fs.watch` on a network
 * share delivers events late, once, or never. A scan is a `stat` per known file, so the steady
 * state costs one syscall per book per interval, and the hashing only happens for files that are
 * new or changed.
 */

export interface WatcherDeps {
  db: Db;
  config: Config;
  logger: {
    info: (payload: object, message: string) => void;
    warn: (payload: object, message: string) => void;
  };
}

export interface ScanReport {
  scanned: number;
  linked: number;
  skipped: number;
  failed: number;
}

export interface LinkWatcher {
  /** One pass over the folder. Exposed so a test (or a route) can run it without waiting. */
  scanOnce: () => Promise<ScanReport>;
  stop: () => void;
}

/** A scan stops here rather than turning one timer tick into an hour of work. */
const MAX_FILES_PER_SCAN = 5000;
/** Give up on a file after this many failures, so one broken book cannot log forever. */
const MAX_ATTEMPTS = 3;
/** A file touched this recently is probably still being written; wait for the next scan. */
const SETTLE_MS = 5000;

interface FileStamp {
  size: number;
  mtimeMs: number;
}

export function startLinkWatcher(
  deps: WatcherDeps,
  options: { initialScan?: boolean } = {},
): LinkWatcher {
  const dir = deps.config.watchDir;
  if (!dir) {
    return { scanOnce: async () => ({ scanned: 0, linked: 0, skipped: 0, failed: 0 }), stop: () => undefined };
  }

  const seen = new Map<string, FileStamp>();
  const attempts = new Map<string, number>();
  let running = false;
  /** The last scan-level failure, so an unmounted folder warns once rather than every interval. */
  let lastScanError: string | null = null;

  const scanOnce = async (): Promise<ScanReport> => {
    const report: ScanReport = { scanned: 0, linked: 0, skipped: 0, failed: 0 };
    // A slow scan must not stack up behind the timer: the next tick simply waits its turn.
    if (running) return report;
    running = true;

    try {
      const files = await collect(dir);
      report.scanned = files.length;
      const blobs = new BlobRepo(deps.db, deps.config.libraryDir);

      for (const file of files) {
        const known = seen.get(file.path);
        if (known && known.size === file.size && known.mtimeMs === file.mtimeMs) {
          report.skipped += 1;
          continue;
        }
        // Already linked in an earlier run: the map is per-process, the database is not.
        if (blobs.byExternalPath(file.path)) {
          seen.set(file.path, { size: file.size, mtimeMs: file.mtimeMs });
          report.skipped += 1;
          continue;
        }
        if (Date.now() - file.mtimeMs < SETTLE_MS) {
          report.skipped += 1;
          continue;
        }

        try {
          const result = await ingestFile(deps, {
            sourcePath: file.path,
            filename: file.name,
            userId: null,
            mode: 'auto',
            link: true,
          });
          seen.set(file.path, { size: file.size, mtimeMs: file.mtimeMs });
          attempts.delete(file.path);
          report.linked += 1;
          deps.logger.info({ path: file.path, title: result.title, bookId: result.bookId }, 'watch: linked');
        } catch (err) {
          // Same bytes already catalogued (the same book under two names, or a file that was
          // imported by hand earlier). Not an error, and not worth retrying every scan.
          if (err instanceof AppError && err.code === 'duplicate_content') {
            seen.set(file.path, { size: file.size, mtimeMs: file.mtimeMs });
            report.skipped += 1;
            continue;
          }
          const count = (attempts.get(file.path) ?? 0) + 1;
          attempts.set(file.path, count);
          report.failed += 1;
          deps.logger.warn(
            { path: file.path, attempts: count, err: err instanceof Error ? err.message : String(err) },
            'watch: import failed',
          );
          if (count >= MAX_ATTEMPTS) seen.set(file.path, { size: file.size, mtimeMs: file.mtimeMs });
        }
      }
      lastScanError = null;
    } catch (err) {
      // An unmounted folder is a normal state on a NAS that just rebooted, not a crash — and not
      // something to repeat every minute either.
      const message = err instanceof Error ? err.message : String(err);
      if (message !== lastScanError) {
        lastScanError = message;
        deps.logger.warn({ dir, err: message }, 'watch: scan failed');
      }
    } finally {
      running = false;
    }

    return report;
  };

  deps.logger.info({ dir, intervalSeconds: deps.config.watchIntervalSeconds }, 'watch: folder enabled');
  // A first pass at boot, so a restart picks up what was dropped while the server was down without
  // waiting a whole interval. Off in tests, which drive the scan themselves.
  if (options.initialScan !== false) void scanOnce();
  const timer = setInterval(() => {
    void scanOnce();
  }, deps.config.watchIntervalSeconds * 1000);

  return { scanOnce, stop: () => clearInterval(timer) };
}

/** Depth-first walk, skipping dot-directories and stopping at the per-scan cap. */
async function collect(root: string): Promise<{ path: string; name: string; size: number; mtimeMs: number }[]> {
  const out: { path: string; name: string; size: number; mtimeMs: number }[] = [];
  // The root is read without a catch: a missing folder (an unmounted share) has to surface as a
  // scan failure, because an empty list would read as "nothing new" and hide it forever.
  const rootEntries = await readdir(root, { withFileTypes: true });
  const stack = [{ dir: root, entries: rootEntries }];

  while (stack.length > 0 && out.length < MAX_FILES_PER_SCAN) {
    const current = stack.pop()!;
    for (const entry of current.entries) {
      if (entry.name.startsWith('.')) continue;
      const full = join(current.dir, entry.name);
      if (entry.isDirectory()) {
        // A subdirectory that cannot be read (permissions, a dangling mount) is skipped, not fatal.
        await readdir(full, { withFileTypes: true })
          .then((entries) => stack.push({ dir: full, entries }))
          .catch(() => undefined);
        continue;
      }
      if (!entry.isFile()) continue;
      const extension = entry.name.split('.').pop()?.toLowerCase() ?? '';
      if (!EBOOK_EXTENSIONS.has(extension)) continue;
      try {
        const info = await stat(full);
        out.push({ path: full, name: entry.name, size: info.size, mtimeMs: info.mtimeMs });
      } catch {
        // Vanished between readdir and stat; the next scan will see it or not.
      }
      if (out.length >= MAX_FILES_PER_SCAN) break;
    }
  }

  return out;
}
