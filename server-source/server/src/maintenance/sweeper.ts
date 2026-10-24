import { join } from 'node:path';
import { existsSync } from 'node:fs';
import { mkdir, rename, rm, unlink } from 'node:fs/promises';
import type { Config } from '../config.ts';
import type { Db } from '../db/index.ts';

/**
 * Retention of everything the API only ever *adds* to.
 *
 * Deleting a book writes a tombstone (`books.deleted_at`) and touches nothing else, which is what
 * `/sync/delta` needs and what keeps another device's half-finished download alive. The bytes are
 * freed here instead, on a clock, in three steps that are individually idempotent:
 *
 *   retire  — a blob nothing reachable points at any more gets `deleted_at = now`. The file stays
 *             on disk, so an in-flight range request keeps succeeding for the whole retention
 *             window; `BlobRepo.existing` starts ignoring the row, so identical bytes re-uploaded
 *             in that window become a new row rather than a resurrected one.
 *   reclaim — past the window the file moves to `trash/<blobId>` and any `book_files` row still
 *             pointing at it goes `quarantined`, because a row whose bytes are gone must not keep
 *             claiming `status = 'ready'`.
 *   purge   — the trash copy is unlinked once it has been there long enough to prove the move was
 *             not interrupted by a crash between the rename and the unlink.
 *
 * Collection is by *reachability*, never by `refcount`: nothing in the request path decrements a
 * book's blob count (only the two cover-replacement sites do), so a `refcount = 0` query would
 * find a deleted book's file and leave it on disk forever.
 */

const DAY_MS = 86_400_000;

/** Superseded device rows outlive their usefulness by this much, so "which phone was revoked and
 *  when" can still be answered by support. The refresh hash in them is already worthless. */
const DEVICE_GRACE_DAYS = 30;
/** Terminal ingest jobs are kept this long purely as a log of what failed.
 *  `operation_logs` is not pruned because nothing writes to it yet — see the ledger. */
const JOB_HISTORY_DAYS = 30;
/** How long a reclaimed file sits in `trash/`. This is crash-safety, not readability — a day is
 *  more than enough to tell "we moved it" from "someone deleted it behind our back". */
const TRASH_GRACE_HOURS = 24;
/** An expired upload session's temp tree is worthless immediately; this only avoids racing a
 *  retry that is still uploading into the same directory. */
const TMP_GRACE_HOURS = 24;

const iso = (ms: number) => new Date(ms).toISOString();

export interface SweepDeps {
  db: Db;
  config: Config;
  logger?: { info: (obj: unknown, msg?: string) => void; warn: (obj: unknown, msg?: string) => void };
}

export interface SweepReport {
  retired: number;
  reclaimed: number;
  purged: number;
  bytesReleased: number;
  uploadSessionsExpired: number;
  tmpFilesRemoved: number;
  devicesRemoved: number;
  jobsPruned: number;
}

interface BlobRef {
  id: string;
  sha256: string;
  storage_path: string;
  size_bytes: number;
}

/** A blob is reachable only through a live book: its primary/secondary files, the shared cover,
 *  or one reader's personal override. `queued`/`running` jobs hold a reference so an import in
 *  progress cannot be collected out from under itself.
 *
 *  A file that a *newer* primary of the same format has replaced does not hold its blob. That is
 *  the row `mode=replace` leaves behind — a previous version, not a second format — and counting
 *  it as reachable is what made every re-import permanent: the superseded file stayed `ready`
 *  forever, its bytes were never collected, and the library grew without bound. The direction is
 *  load-bearing: a file *newer* than the primary is the odd one out of an `attach`, which is a
 *  deliberate second copy, and must be kept. Either way the row itself stays `ready` and
 *  downloadable for the whole retention window; only the bytes are on the clock. */
const UNREACHABLE = `
  SELECT b.id, b.sha256, b.storage_path, b.size_bytes
    FROM blobs b
   WHERE b.deleted_at IS NULL
     -- External blobs are files the user owns, catalogued in place. Nothing here may retire,
     -- reclaim or trash one: the library does not own those bytes and deleting a book must not
     -- delete them either.
     AND b.external = 0
     AND NOT EXISTS (
       SELECT 1 FROM book_files bf JOIN books bk ON bk.id = bf.book_id
        WHERE bf.blob_id = b.id AND bf.status = 'ready' AND bk.deleted_at IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM book_files pf
             WHERE pf.book_id = bf.book_id
               AND pf.format = bf.format
               AND pf.is_primary = 1
               AND pf.status = 'ready'
               AND pf.created_at > bf.created_at))
     AND NOT EXISTS (
       SELECT 1 FROM books bc WHERE bc.cover_blob_id = b.id AND bc.deleted_at IS NULL)
     AND NOT EXISTS (
       SELECT 1 FROM user_book_states ubs JOIN books bk2 ON bk2.id = ubs.book_id
        WHERE ubs.cover_override_blob_id = b.id AND bk2.deleted_at IS NULL)
     AND NOT EXISTS (
       SELECT 1 FROM ingest_jobs ij
        WHERE ij.blob_id = b.id AND ij.state IN ('queued', 'running'))`;

export async function sweepOnce(deps: SweepDeps, now: Date = new Date()): Promise<SweepReport> {
  const { db, config, logger } = deps;
  const at = now.getTime();
  const retentionCutoff = iso(at - config.fileRetentionDays * DAY_MS);
  const report: SweepReport = {
    retired: 0,
    reclaimed: 0,
    purged: 0,
    bytesReleased: 0,
    uploadSessionsExpired: 0,
    tmpFilesRemoved: 0,
    devicesRemoved: 0,
    jobsPruned: 0,
  };

  // --- retire -----------------------------------------------------------------
  const doomed = db.all<BlobRef>(UNREACHABLE);
  if (doomed.length > 0) {
    const stamp = iso(at);
    db.tx(() => {
      for (const blob of doomed) {
        db.run('UPDATE blobs SET deleted_at = ?, refcount = 0 WHERE id = ? AND deleted_at IS NULL', [
          stamp,
          blob.id,
        ]);
      }
    });
    report.retired = doomed.length;
  }

  // --- reclaim ----------------------------------------------------------------
  const overdue = db.all<BlobRef>(
    `SELECT id, sha256, storage_path, size_bytes FROM blobs
      WHERE deleted_at IS NOT NULL AND deleted_at < ?`,
    [retentionCutoff],
  );
  for (const blob of overdue) {
    const from = join(config.libraryDir, blob.storage_path);
    const to = join(config.paths.trash, blob.id);
    try {
      let moved = false;
      if (existsSync(from)) {
        await mkdir(config.paths.trash, { recursive: true });
        await rename(from, to);
        moved = true;
      }
      // Runs whether or not the move happened here: a crash between the rename and this update
      // would otherwise leave a row claiming `ready` for bytes that are already in the trash.
      db.run('UPDATE book_files SET status = ?, updated_at = ? WHERE blob_id = ? AND status = ?', [
        'quarantined',
        iso(at),
        blob.id,
        'ready',
      ]);
      if (moved) {
        report.reclaimed += 1;
        report.bytesReleased += blob.size_bytes;
      }
    } catch (err) {
      // One unreadable file must not stop the rest of the pass, and must not look like a success.
      logger?.warn({ err, blobId: blob.id }, 'sweep: reclaim failed');
    }
  }

  // --- purge ------------------------------------------------------------------
  // Measured from the same `deleted_at` as the move, but shifted by the whole retention window, so
  // the two grace periods compose: a file is never moved and unlinked in one pass. That gap is
  // the only thing that distinguishes "we retired this" from "this went missing".
  const trashCutoff = iso(at - (config.fileRetentionDays * DAY_MS + TRASH_GRACE_HOURS * 3_600_000));
  const settled = db.all<{ id: string }>(
    `SELECT id FROM blobs WHERE deleted_at IS NOT NULL AND deleted_at < ?`,
    [trashCutoff],
  );
  for (const blob of settled) {
    const inTrash = join(config.paths.trash, blob.id);
    try {
      if (existsSync(inTrash)) {
        await unlink(inTrash);
        report.purged += 1;
      }
    } catch (err) {
      logger?.warn({ err, blobId: blob.id }, 'sweep: purge failed');
    }
  }

  // --- upload sessions --------------------------------------------------------
  const expiredStamp = iso(at);
  report.uploadSessionsExpired = db.run(
    `UPDATE upload_sessions SET status = 'expired', updated_at = ?
      WHERE status IN ('receiving', 'received', 'processing') AND expires_at < ?`,
    [expiredStamp, expiredStamp],
  ).changes;

  // Selected by `expires_at`, which is written once, rather than by `updated_at`: the status flip
  // just above rewrites `updated_at`, so a row keyed on it would be pushed out of reach every time
  // it was looked at. A session can also be long expired and still own a temp directory, if the
  // process died between the flip and the unlink.
  const staleUploads = db.all<{ id: string }>(
    `SELECT id FROM upload_sessions
      WHERE status IN ('expired', 'failed') AND expires_at < ?`,
    [iso(at - TMP_GRACE_HOURS * 3_600_000)],
  );
  for (const session of staleUploads) {
    // Built from the row id, never from `tmp_path`, so a database that names a path outside the
    // upload tree cannot make this delete something it was never meant to touch.
    const tmpDir = join(config.paths.uploadsTmp, session.id);
    try {
      if (existsSync(tmpDir)) {
        await rm(tmpDir, { recursive: true, force: true });
        report.tmpFilesRemoved += 1;
      }
    } catch (err) {
      logger?.warn({ err, uploadId: session.id }, 'sweep: temp cleanup failed');
    }
  }

  // --- device rows and job history -------------------------------------------
  report.devicesRemoved = db.run(
    `DELETE FROM auth_devices
      WHERE (revoked_at IS NOT NULL OR expires_at < ?)
        AND rotated_from_id IS NOT NULL
        AND COALESCE(last_used_at, created_at) < ?`,
    [expiredStamp, iso(at - DEVICE_GRACE_DAYS * DAY_MS)],
  ).changes;

  report.jobsPruned = db.run(
    `DELETE FROM ingest_jobs
      WHERE state IN ('succeeded', 'failed', 'cancelled')
        AND finished_at IS NOT NULL AND finished_at < ?`,
    [iso(at - JOB_HISTORY_DAYS * DAY_MS)],
  ).changes;

  // The -wal file otherwise only shrinks when the last connection closes, which on a long-running
  // container is never.
  db.exec('PRAGMA wal_checkpoint(TRUNCATE)');

  if (logger && Object.values(report).some((n) => n > 0)) logger.info({ sweep: report }, 'retention sweep');
  return report;
}

/**
 * Starts the interval only. Deliberately not part of `buildApp`: the HTTP tests build the app
 * thousands of times and none of them should be racing a timer that deletes files.
 *
 * The first pass runs after a short delay rather than at boot, so migrations, the bootstrap admin
 * and the initial listen are never competing with filesystem work.
 */
export function startSweeper(deps: SweepDeps): () => void {
  const { config, logger } = deps;
  let running = false;
  const tick = async () => {
    if (running) return;
    running = true;
    try {
      await sweepOnce(deps, new Date());
    } catch (err) {
      logger?.warn({ err }, 'sweep failed');
    } finally {
      running = false;
    }
  };
  const interval = Math.max(1, config.sweepIntervalHours) * 3_600_000;
  const first = setTimeout(tick, 30_000);
  const timer = setInterval(tick, interval);
  // Neither timer may hold the process open — `unref` is what lets SIGINT exit normally.
  first.unref();
  timer.unref();
  return () => {
    clearTimeout(first);
    clearInterval(timer);
  };
}
