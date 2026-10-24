import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { loadConfig, type Config } from '../src/config.ts';
import type { Db } from '../src/db/index.ts';
import { sweepOnce } from '../src/maintenance/sweeper.ts';
import { insertBook, insertUser, openMigratedDb } from './helpers/db.ts';

/**
 * Retention, tested against real files on a real disk.
 *
 * The interesting property is not that anything gets deleted — it is the ordering that decides
 * *when*, because every one of these steps is a data-loss bug if it runs one tick early. So the
 * tests walk the lifecycle by moving an injected clock forward rather than by poking at internals,
 * and the first cases assert what must **never** happen.
 *
 * Each case gets its own database and its own library directory: the sweep report counts rows
 * globally, so a shared fixture would make one test's leftovers another test's assertion.
 */

const DAY = 86_400_000;
const NOW = new Date('2026-06-01T00:00:00.000Z');
const at = (ms: number) => new Date(ms).toISOString();
const daysBeforeNow = (days: number) => at(NOW.getTime() - days * DAY);
const daysAfterNow = (days: number) => new Date(NOW.getTime() + days * DAY);

const shaOf = (seed: string) => seed.padEnd(64, '0');
const pathOf = (sha: string) => `blobs/${sha.slice(0, 2)}/${sha.slice(2, 4)}/${sha}`;

let dir: string;
let config: Config;
let db: Db;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-sweep-'));
  config = loadConfig({
    NODE_ENV: 'test',
    EBOOK_DATA_DIR: dir,
    EBOOK_LIBRARY_DIR: dir,
    EBOOK_FILE_RETENTION_DAYS: '7',
  });
  db = openMigratedDb();
  insertUser(db, 's-owner');
  mkdirSync(join(config.paths.uploadsTmp), { recursive: true });
});

afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
});

/** Writes the bytes as well as the row: a sweeper that only looked at the database would pass
 *  every test here while still failing on the case that matters, a row whose file is gone. */
function seedBlob(input: {
  id: string;
  seed: string;
  sizeBytes?: number;
  deletedAt?: string | null;
  format?: string;
  writeFile?: boolean;
}): { id: string; sha: string; absolute: string; trash: string } {
  const sha = shaOf(input.seed);
  const absolute = join(dir, pathOf(sha));
  if (input.writeFile !== false) {
    mkdirSync(dirname(absolute), { recursive: true });
    writeFileSync(absolute, `bytes-of-${input.id}`);
  }
  db.run(
    `INSERT INTO blobs (id, sha256, size_bytes, storage_path, media_type, format, refcount, created_at, deleted_at)
     VALUES (?, ?, ?, ?, 'application/octet-stream', ?, 1, ?, ?)`,
    [
      input.id,
      sha,
      input.sizeBytes ?? 1024,
      pathOf(sha),
      input.format ?? 'txt',
      daysBeforeNow(60),
      input.deletedAt ?? null,
    ],
  );
  return { id: input.id, sha, absolute, trash: join(config.paths.trash, input.id) };
}

function seedFile(input: {
  id: string;
  bookId: string;
  blobId: string;
  sha: string;
  isPrimary?: boolean;
  format?: string;
  createdAt?: string;
}): void {
  const createdAt = input.createdAt ?? daysBeforeNow(60);
  db.run(
    `INSERT INTO book_files (id, book_id, blob_id, format, media_type, filename, size_bytes, sha256,
                             drm, is_primary, status, created_at, updated_at)
     VALUES (?, ?, ?, ?, 'text/plain', ?, 1024, ?, 0, ?, 'ready', ?, ?)`,
    [
      input.id,
      input.bookId,
      input.blobId,
      input.format ?? 'txt',
      `${input.id}.txt`,
      input.sha,
      input.isPrimary === false ? 0 : 1,
      createdAt,
      createdAt,
    ],
  );
}

/** A book, its blob and the file row joining them — the shape the library actually stores. */
function seedShelvedBook(bookId: string, blobId: string, seed: string, deletedAt: string | null) {
  insertBook(db, bookId, { deletedAt });
  const blob = seedBlob({ id: `${blobId}-blob`, seed });
  seedFile({ id: `${bookId}-file`, bookId, blobId: blob.id, sha: blob.sha });
  return blob;
}

const fileStatus = (fileId: string) =>
  db.get<{ status: string }>('SELECT status FROM book_files WHERE id = ?', [fileId])?.status;
const blobDeletedAt = (blobId: string) =>
  db.get<{ deleted_at: string | null }>('SELECT deleted_at FROM blobs WHERE id = ?', [blobId])?.deleted_at;

describe('a file is freed by reachability, never by the passage of time alone', () => {
  it('leaves a live book entirely alone', async () => {
    insertBook(db, 's-live');
    const blob = seedBlob({ id: 's-live-blob', seed: 'a1' });
    seedFile({ id: 's-live-file', bookId: 's-live', blobId: 's-live-blob', sha: blob.sha });

    const report = await sweepOnce({ db, config }, NOW);

    expect(report).toMatchObject({ retired: 0, reclaimed: 0, purged: 0, bytesReleased: 0 });
    expect(existsSync(blob.absolute)).toBe(true);
    expect(blobDeletedAt('s-live-blob')).toBeNull();
    expect(fileStatus('s-live-file')).toBe('ready');
  });

  it('keeps bytes two books share when only one of them is deleted', async () => {
    insertBook(db, 's-keep');
    insertBook(db, 's-gone', { deletedAt: daysBeforeNow(40) });
    const shared = seedBlob({ id: 's-shared', seed: 'b2' });
    seedFile({ id: 's-keep-file', bookId: 's-keep', blobId: 's-shared', sha: shared.sha });
    seedFile({ id: 's-gone-file', bookId: 's-gone', blobId: 's-shared', sha: shared.sha });

    const report = await sweepOnce({ db, config }, NOW);

    expect(report.retired).toBe(0);
    expect(blobDeletedAt('s-shared')).toBeNull();
    expect(existsSync(shared.absolute)).toBe(true);
  });

  it('treats a cover, a personal override and an unfinished job as references', async () => {
    insertBook(db, 's-cover');
    insertBook(db, 's-orphan');
    const cover = seedBlob({ id: 's-cover-blob', seed: 'c3', format: 'cover' });
    const personal = seedBlob({ id: 's-personal-blob', seed: 'c4', format: 'cover' });
    const job = seedBlob({ id: 's-job-blob', seed: 'c5' });
    db.run('UPDATE books SET cover_blob_id = ? WHERE id = ?', [cover.id, 's-cover']);
    db.run(
      `INSERT INTO user_book_states (user_id, book_id, cover_override_blob_id, created_at, updated_at, rev)
       VALUES ('s-owner', 's-orphan', ?, ?, ?, 1)`,
      [personal.id, daysBeforeNow(20), daysBeforeNow(20)],
    );
    db.run(
      `INSERT INTO ingest_jobs (id, kind, blob_id, state, created_at)
       VALUES ('s-job', 'cover', ?, 'running', ?)`,
      [job.id, daysBeforeNow(20)],
    );

    const report = await sweepOnce({ db, config }, NOW);

    expect(report.retired).toBe(0);
    for (const blob of [cover, personal, job]) expect(blobDeletedAt(blob.id)).toBeNull();
  });

  it('retires a deleted book first and only unlinks it after the whole window', async () => {
    const blob = seedShelvedBook('s-life', 's-life', 'd6', daysBeforeNow(1));

    // Pass 1, one day after the delete: the clock starts, the bytes stay downloadable.
    const first = await sweepOnce({ db, config }, NOW);
    expect(first).toMatchObject({ retired: 1, reclaimed: 0, purged: 0, bytesReleased: 0 });
    expect(blobDeletedAt('s-life-blob')).toBe(at(NOW.getTime()));
    expect(existsSync(blob.absolute)).toBe(true);
    expect(fileStatus('s-life-file')).toBe('ready');

    // Pass 2, past the 7-day window: moved aside, and the row stops advertising `ready`.
    const second = await sweepOnce({ db, config }, daysAfterNow(8));
    expect(second).toMatchObject({ retired: 0, reclaimed: 1, bytesReleased: 1024 });
    expect(existsSync(blob.absolute)).toBe(false);
    expect(existsSync(blob.trash)).toBe(true);
    expect(fileStatus('s-life-file')).toBe('quarantined');

    // Pass 3: the trash copy is what actually frees the disk, a day after the move.
    const third = await sweepOnce({ db, config }, daysAfterNow(10));
    expect(third.purged).toBe(1);
    expect(existsSync(blob.trash)).toBe(false);
  });

  it('holds the trash copy for a day before purging, so a crash between the steps is visible', async () => {
    const blob = seedShelvedBook('s-crash', 's-crash', 'e7', daysBeforeNow(1));
    await sweepOnce({ db, config }, NOW);

    // Reclaimed, but not yet old enough in the trash to be unlinked.
    const early = await sweepOnce({ db, config }, daysAfterNow(8));
    expect(early).toMatchObject({ reclaimed: 1, purged: 0 });
    expect(existsSync(blob.trash)).toBe(true);

    const late = await sweepOnce({ db, config }, daysAfterNow(9));
    expect(late.purged).toBe(1);
  });

  it('does nothing twice', async () => {
    seedShelvedBook('s-idem', 's-idem', 'f8', daysBeforeNow(40));
    const when = daysAfterNow(30);
    const first = await sweepOnce({ db, config }, when);
    const again = await sweepOnce({ db, config }, when);

    expect(first).toMatchObject({ retired: 1, reclaimed: 0 });
    expect(again).toMatchObject({ retired: 0, reclaimed: 0, purged: 0, bytesReleased: 0 });
  });

  it('still quarantines a row whose file vanished from disk on its own', async () => {
    const blob = seedBlob({ id: 's-lost-blob', seed: 'g9', deletedAt: daysBeforeNow(10) });
    insertBook(db, 's-lost', { deletedAt: daysBeforeNow(40) });
    seedFile({ id: 's-lost-file', bookId: 's-lost', blobId: 's-lost-blob', sha: blob.sha });
    rmSync(blob.absolute, { force: true });

    const report = await sweepOnce({ db, config }, NOW);

    // Nothing was actually freed, so nothing is claimed as freed — but the row must stop lying.
    expect(report.reclaimed).toBe(0);
    expect(report.bytesReleased).toBe(0);
    expect(fileStatus('s-lost-file')).toBe('quarantined');
  });
});

describe('the housekeeping rows', () => {
  it('expires upload sessions on their own clock and clears the temp tree', async () => {
    const stale = join(config.paths.uploadsTmp, 'u-stale');
    const fresh = join(config.paths.uploadsTmp, 'u-fresh');
    for (const path of [stale, fresh]) {
      mkdirSync(path, { recursive: true });
      writeFileSync(join(path, 'part'), 'x');
    }
    const insert = (id: string, expiredAt: string) =>
      db.run(
        `INSERT INTO upload_sessions (id, user_id, filename, tmp_path, status, created_at, updated_at, expires_at)
         VALUES (?, 's-owner', 'novel.txt', ?, 'receiving', ?, ?, ?)`,
        [id, join(stale, 'part'), daysBeforeNow(10), daysBeforeNow(10), expiredAt],
      );
    insert('u-stale', daysBeforeNow(2));
    insert('u-fresh', at(NOW.getTime() - 3_600_000));

    const report = await sweepOnce({ db, config }, NOW);

    // Both rows cross `expires_at`, but only the long-dead one is past the grace that protects a
    // retry still uploading into the same directory.
    expect(report.uploadSessionsExpired).toBe(2);
    expect(report.tmpFilesRemoved).toBe(1);
    expect(existsSync(stale)).toBe(false);
    expect(existsSync(fresh)).toBe(true);
  });

  it('drops only device rows that were superseded and are no longer usable', async () => {
    db.run(
      `INSERT INTO auth_devices (id, user_id, kind, refresh_hash, family_id, created_at, expires_at)
       VALUES ('d-active', 's-owner', 'device', 'h1', 'f1', ?, ?)`,
      [daysBeforeNow(200), at(NOW.getTime() + 30 * DAY)],
    );
    const superseded = (id: string, hash: string, daysAgo: number) => {
      const stamp = daysBeforeNow(daysAgo);
      return db.run(
        `INSERT INTO auth_devices (id, user_id, kind, refresh_hash, family_id, rotated_from_id,
                                   revoked_at, last_used_at, created_at, expires_at)
         VALUES (?, 's-owner', 'device', ?, 'f1', 'd-active', ?, ?, ?, ?)`,
        [id, hash, stamp, stamp, stamp, stamp],
      );
    };
    superseded('d-old', 'h2', 100);
    superseded('d-recent', 'h3', 2);

    const report = await sweepOnce({ db, config }, NOW);

    const kept = db.all<{ id: string }>('SELECT id FROM auth_devices ORDER BY id').map((r) => r.id);
    expect(report.devicesRemoved).toBe(1);
    expect(kept).toEqual(['d-active', 'd-recent']);
  });

  it('forgets terminal ingest jobs older than a month', async () => {
    const job = (id: string, daysAgo: number) =>
      db.run(
        `INSERT INTO ingest_jobs (id, kind, state, created_at, finished_at)
         VALUES (?, 'ingest_file', 'failed', ?, ?)`,
        [id, daysBeforeNow(daysAgo), daysBeforeNow(daysAgo)],
      );
    job('j-old', 45);
    job('j-new', 2);

    const report = await sweepOnce({ db, config }, NOW);

    expect(report.jobsPruned).toBe(1);
    expect(db.get<{ id: string }>("SELECT id FROM ingest_jobs WHERE id = 'j-new'")?.id).toBe('j-new');
  });
});

describe('a file a newer primary replaced does not hold its blob', () => {
  /**
   * The disk-leak shape: `mode=replace` demotes the previous file to `is_primary = 0` but leaves
   * it `ready`, so it counted as reachable forever and every re-import of a book was permanent.
   * The rule that fixes it has to tell a previous version from a second format, which is the
   * whole difficulty — both are a non-primary row on the same book.
   */
  it('retires the superseded version, keeps the new primary and every other format', async () => {
    insertBook(db, 's-rep');
    const old = seedBlob({ id: 's-rep-old-blob', seed: 'h1' });
    const fresh = seedBlob({ id: 's-rep-new-blob', seed: 'h2' });
    const secondFormat = seedBlob({ id: 's-rep-epub-blob', seed: 'h3', format: 'epub' });
    seedFile({
      id: 's-rep-old',
      bookId: 's-rep',
      blobId: old.id,
      sha: old.sha,
      isPrimary: false,
      createdAt: daysBeforeNow(30),
    });
    seedFile({
      id: 's-rep-new',
      bookId: 's-rep',
      blobId: fresh.id,
      sha: fresh.sha,
      isPrimary: true,
      createdAt: daysBeforeNow(10),
    });
    seedFile({
      id: 's-rep-epub',
      bookId: 's-rep',
      blobId: secondFormat.id,
      sha: secondFormat.sha,
      isPrimary: false,
      format: 'epub',
      createdAt: daysBeforeNow(20),
    });

    const first = await sweepOnce({ db, config }, NOW);

    expect(first.retired).toBe(1);
    expect(blobDeletedAt(old.id)).not.toBeNull();
    expect(blobDeletedAt(fresh.id)).toBeNull();
    expect(blobDeletedAt(secondFormat.id)).toBeNull();
    // The row itself stays downloadable until the window passes — only the bytes are on the clock.
    expect(fileStatus('s-rep-old')).toBe('ready');
    expect(existsSync(old.absolute)).toBe(true);

    const second = await sweepOnce({ db, config }, daysAfterNow(8));
    expect(second.reclaimed).toBe(1);
    expect(fileStatus('s-rep-old')).toBe('quarantined');
    expect(existsSync(old.absolute)).toBe(false);
    expect(existsSync(fresh.absolute)).toBe(true);
    expect(existsSync(secondFormat.absolute)).toBe(true);
  });

  it('keeps a file newer than the primary — an attach is a second copy, not a leftover', async () => {
    // The mirror image, and the reason the rule compares timestamps rather than just looking for
    // a non-primary row: `mode=attach` adds a *newer* file without promoting it, and that file is
    // a deliberate second copy. Reclaiming it would be the bug this fix is supposed to prevent.
    insertBook(db, 's-att');
    const primary = seedBlob({ id: 's-att-old-blob', seed: 'i1' });
    const attached = seedBlob({ id: 's-att-new-blob', seed: 'i2' });
    seedFile({
      id: 's-att-primary',
      bookId: 's-att',
      blobId: primary.id,
      sha: primary.sha,
      isPrimary: true,
      createdAt: daysBeforeNow(30),
    });
    seedFile({
      id: 's-att-attached',
      bookId: 's-att',
      blobId: attached.id,
      sha: attached.sha,
      isPrimary: false,
      createdAt: daysBeforeNow(5),
    });

    const report = await sweepOnce({ db, config }, NOW);

    expect(report.retired).toBe(0);
    expect(blobDeletedAt(primary.id)).toBeNull();
    expect(blobDeletedAt(attached.id)).toBeNull();
  });

  it('still shares bytes with another book that holds the same file as its primary', async () => {
    // The shared-bytes case, now that a book can no longer hold the same blob twice: book A
    // replaced its copy of the shared file with a new one, while book B still has those exact
    // bytes as its primary. Reachability is per blob, so B's row holds it for both.
    insertBook(db, 's-rep2');
    insertBook(db, 's-live2');
    const shared = seedBlob({ id: 's-shared2', seed: 'j1' });
    const replacement = seedBlob({ id: 's-rep2-new-blob', seed: 'j2' });
    seedFile({
      id: 's-rep2-old',
      bookId: 's-rep2',
      blobId: shared.id,
      sha: shared.sha,
      isPrimary: false,
      createdAt: daysBeforeNow(30),
    });
    seedFile({
      id: 's-rep2-new',
      bookId: 's-rep2',
      blobId: replacement.id,
      sha: replacement.sha,
      isPrimary: true,
      createdAt: daysBeforeNow(10),
    });
    seedFile({ id: 's-live2-file', bookId: 's-live2', blobId: shared.id, sha: shared.sha });

    const report = await sweepOnce({ db, config }, NOW);

    expect(report.retired).toBe(0);
    expect(blobDeletedAt(shared.id)).toBeNull();
    expect(blobDeletedAt(replacement.id)).toBeNull();
  });
});
