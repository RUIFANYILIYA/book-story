import { existsSync, mkdtempSync, readFileSync, rmSync, unlinkSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { loadConfig, type Config } from '../src/config.ts';
import { buildApp } from '../src/app.ts';
import { openMigratedDb } from './helpers/db.ts';
import { UserRepo } from '../src/db/repositories/user.repo.ts';
import { BlobRepo } from '../src/db/repositories/blob.repo.ts';
import { startLinkWatcher } from '../src/library/link-watcher.ts';
import { sweepOnce } from '../src/maintenance/sweeper.ts';
import { bearer, seedUser, signIn } from './helpers/app.ts';

/**
 * Books catalogued where they already are.
 *
 * The NAS case: the files are on the share, and the library should know about them without a
 * second copy. What makes this safe rather than convenient is the set of promises around it — the
 * file is never written, never moved, never deleted, and never collected by the retention sweeper
 * even after its book is gone. Each of those is a test here, because each is the kind of bug that
 * silently eats someone's library.
 */

const BOOK = '夜航船\n\n第一章 起\n\n海面像一块被磨过的铁板，风从桅杆上滑下来。\n';
/** Older than the watcher's "still being written" guard. */
const SETTLED = new Date(Date.now() - 60_000);

let dir: string;
let watchDir: string;
let config: Config;
let db: ReturnType<typeof openMigratedDb>;
let app: FastifyInstance;
let token: string;

const bookPath = (): string => join(watchDir, '夜航船.txt');
const dropBook = (name = '夜航船.txt', text = BOOK): string => {
  const path = join(watchDir, name);
  writeFileSync(path, text);
  utimesSync(path, SETTLED, SETTLED);
  return path;
};

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-link-'));
  watchDir = join(dir, 'watch');
  const { mkdirSync } = await import('node:fs');
  mkdirSync(watchDir, { recursive: true });

  config = loadConfig({
    NODE_ENV: 'test',
    EBOOK_LOG_LEVEL: 'silent',
    EBOOK_DATA_DIR: join(dir, 'db'),
    EBOOK_LIBRARY_DIR: join(dir, 'library'),
    EBOOK_WATCH_DIR: watchDir,
    EBOOK_WATCH_INTERVAL: '60',
    // The manual route resolves paths inside the import root; pointing it at the same folder is
    // what an operator does when they want both ways to reach the same books.
    EBOOK_IMPORT_DIR: watchDir,
    EBOOK_JWT_SECRET: 'test-secret-test-secret-test-secret-1234567890',
    EBOOK_MASTER_KEY: 'test-master-key-test-master-key-1234567890',
  });
  db = openMigratedDb();
  app = await buildApp({ config, db });
  await app.ready();

  const users = new UserRepo(db);
  await seedUser(users, { username: 'owner', password: 'owner-password-1', role: 'admin' });
  token = (await signIn(app, 'owner', 'owner-password-1')).accessToken;
});

afterAll(async () => {
  await app.close();
  rmSync(dir, { recursive: true, force: true });
});

const bookIdFor = (title: string): string | undefined =>
  db.get<{ id: string }>('SELECT id FROM books WHERE title = ? AND deleted_at IS NULL', [title])?.id;
const fileIdFor = (bookId: string): string | undefined =>
  db.get<{ id: string }>('SELECT id FROM book_files WHERE book_id = ?', [bookId])?.id;
const blobFor = (path: string) =>
  db.get<{ id: string; external: number; storage_path: string; deleted_at: string | null }>(
    'SELECT id, external, storage_path, deleted_at FROM blobs WHERE storage_path = ?',
    [path],
  );

describe('the watch folder', () => {
  it('catalogues a new file without moving or copying it', async () => {
    const path = dropBook();
    const watcher = startLinkWatcher({ db, config, logger: { info: () => undefined, warn: () => undefined } }, {
      initialScan: false,
    });

    try {
      const report = await watcher.scanOnce();

      expect(report.linked).toBe(1);
      // The whole point: the file is still exactly where the user put it.
      expect(existsSync(path)).toBe(true);
      expect(readFileSync(path, 'utf8')).toBe(BOOK);

      const blob = blobFor(path);
      expect(blob?.external).toBe(1);
      expect(blob?.storage_path).toBe(path);
      // Nothing was copied into the library for it: the content-addressed tree is never created.
      expect(existsSync(join(dir, 'library', 'blobs'))).toBe(false);
      const stored = new BlobRepo(db, config.libraryDir).byId(blob!.id);
      expect(new BlobRepo(db, config.libraryDir).absolutePath(stored!)).toBe(path);

      const bookId = bookIdFor('夜航船');
      expect(bookId).toBeTruthy();
      const fileId = fileIdFor(bookId!);
      const download = await app.inject({
        method: 'GET',
        url: `/api/v1/files/${fileId}/download`,
        headers: bearer(token),
      });
      expect(download.statusCode).toBe(200);
      expect(download.rawPayload.toString('utf8')).toBe(BOOK);
    } finally {
      watcher.stop();
    }
  });

  it('does not import the same file twice', async () => {
    const watcher = startLinkWatcher({ db, config, logger: { info: () => undefined, warn: () => undefined } }, {
      initialScan: false,
    });
    try {
      const report = await watcher.scanOnce();
      expect(report.linked).toBe(0);
      expect(report.skipped).toBe(1);
      expect(db.get<{ n: number }>('SELECT count(*) AS n FROM books WHERE title = ?', ['夜航船'])?.n).toBe(1);
    } finally {
      watcher.stop();
    }
  });

  it('picks up a file added while it was running', async () => {
    const watcher = startLinkWatcher({ db, config, logger: { info: () => undefined, warn: () => undefined } }, {
      initialScan: false,
    });
    try {
      dropBook('灯塔.txt', '灯塔\n\n第二章 承\n\n灯塔的光每隔十二秒扫过一次。\n');
      const report = await watcher.scanOnce();

      expect(report.linked).toBe(1);
      expect(bookIdFor('灯塔')).toBeTruthy();
    } finally {
      watcher.stop();
    }
  });

  it('leaves a file alone while it is still being written', async () => {
    const fresh = join(watchDir, '正在下载.txt');
    writeFileSync(fresh, '下载到一半');
    const watcher = startLinkWatcher({ db, config, logger: { info: () => undefined, warn: () => undefined } }, {
      initialScan: false,
    });
    try {
      const report = await watcher.scanOnce();
      expect(report.linked).toBe(0);
      expect(bookIdFor('正在下载')).toBeUndefined();
    } finally {
      watcher.stop();
      unlinkSync(fresh);
    }
  });
});

describe('a linked book', () => {
  it('survives the retention sweeper, which never touches files it does not own', async () => {
    const path = bookPath();
    const blobId = blobFor(path)!.id;

    // Far past the retention window: a copied blob would be retired by now.
    const report = await sweepOnce({ db, config }, new Date(Date.now() + 400 * 86_400_000));

    expect(report.retired).toBe(0);
    expect(blobFor(path)?.deleted_at).toBeNull();
    expect(existsSync(path)).toBe(true);
    expect(db.get<{ n: number }>('SELECT refcount AS n FROM blobs WHERE id = ?', [blobId])?.n).toBeGreaterThan(0);
  });

  it('keeps the file when the book is deleted', async () => {
    const path = bookPath();
    const bookId = bookIdFor('夜航船')!;

    const res = await app.inject({ method: 'DELETE', url: `/api/v1/books/${bookId}`, headers: bearer(token) });
    expect(res.statusCode).toBe(204);

    // Deleted from the shelf, still on the share — deleting a catalogue entry is not deleting a
    // file, and the sweeper is told the same thing.
    expect(bookIdFor('夜航船')).toBeUndefined();
    await sweepOnce({ db, config }, new Date(Date.now() + 400 * 86_400_000));
    expect(existsSync(path)).toBe(true);
    expect(readFileSync(path, 'utf8')).toBe(BOOK);
  });

  it('answers 503 with a readable reason when the file has moved away', async () => {
    const bookId = bookIdFor('灯塔')!;
    const fileId = fileIdFor(bookId)!;
    unlinkSync(join(watchDir, '灯塔.txt'));

    const res = await app.inject({ method: 'GET', url: `/api/v1/files/${fileId}/download`, headers: bearer(token) });

    expect(res.statusCode).toBe(503);
    expect(res.json().error.code).toBe('storage_unavailable');
    expect(res.json().error.message).toContain('linked file');
  });
});

describe('POST /api/v1/import/dir with link: true', () => {
  it('catalogues in place and reports the count', async () => {
    const nested = join(watchDir, 'nested');
    const { mkdirSync } = await import('node:fs');
    mkdirSync(nested, { recursive: true });
    const path = join(nested, '潮汐.txt');
    writeFileSync(path, '潮汐\n\n第三章 转\n\n潮水退去，滩涂上留下密密麻麻的小孔。\n');
    utimesSync(path, SETTLED, SETTLED);

    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/import/dir',
      headers: bearer(token),
      payload: { path: 'nested', link: true },
    });

    expect(res.statusCode).toBe(200);
    expect(res.json().imported).toBe(1);
    expect(existsSync(path)).toBe(true);
    expect(blobFor(path)?.external).toBe(1);
  });
});
