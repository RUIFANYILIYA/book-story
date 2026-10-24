import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { loadConfig } from '../src/config.ts';
import { buildApp } from '../src/app.ts';
import { openMigratedDb } from './helpers/db.ts';
import { UserRepo } from '../src/db/repositories/user.repo.ts';
import { BookRepo } from '../src/db/repositories/book.repo.ts';
import { BlobRepo } from '../src/db/repositories/blob.repo.ts';
import { bearer, seedUser, signIn } from './helpers/app.ts';

/**
 * Progress, bookmarks and delta sync.
 *
 * The properties that matter are the multi-device ones: two devices in different chapters of the
 * same book must not overwrite each other, an offline delete must reach the other device as a
 * tombstone rather than the row silently reappearing, and a stale push must be reported as a
 * conflict instead of quietly discarding whichever position was newer.
 */

let app: FastifyInstance;
let dir: string;
let db: ReturnType<typeof openMigratedDb>;
let token: string;
let otherDeviceToken: string;
let bookId: string;
let fileId: string;

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-sync-'));
  db = openMigratedDb();
  app = await buildApp({
    config: loadConfig({
      NODE_ENV: 'test',
      EBOOK_LOG_LEVEL: 'silent',
      EBOOK_DATA_DIR: join(dir, 'db'),
      EBOOK_LIBRARY_DIR: join(dir, 'library'),
      EBOOK_JWT_SECRET: 'test-secret-test-secret-test-secret-1234567890',
      EBOOK_MASTER_KEY: 'test-master-key-test-master-key-1234567890',
    }),
    db,
  });
  await app.ready();

  const users = new UserRepo(db);
  await seedUser(users, { username: 'reader', password: 'reader-password-1' });
  const first = await signIn(app, 'reader', 'reader-password-1');
  // A second sign-in is a second device: distinct deviceId, same user.
  const second = await signIn(app, 'reader', 'reader-password-1');
  token = first.accessToken;
  otherDeviceToken = second.accessToken;

  const books = new BookRepo(db);
  const blobs = new BlobRepo(db, join(dir, 'library'));
  const sha = 'e'.repeat(64);
  const blob = blobs.register({
    sha256: sha,
    sizeBytes: 2048,
    relativePath: `blobs/ee/ee/${sha}`,
    mediaType: 'text/plain',
    format: 'txt',
    refcount: 0,
  });
  bookId = books.upsertFromMetadata({
    meta: {
      format: 'txt',
      title: '同步测试书',
      contributors: [{ name: '同步作者', role: 'author' }],
      tags: [],
      cover: null,
      source: 'txt',
      confidence: 0.9,
      warnings: [],
      drm: false,
    } as never,
    sha256: sha,
    filename: '同步测试书.txt',
    userId: null,
    mode: 'create',
  }).bookId;
  fileId = books.attachFile({
    bookId,
    blobId: blob.id,
    sha256: sha,
    sizeBytes: 2048,
    format: 'txt',
    mediaType: 'text/plain',
    filename: '同步测试书.txt',
  });
});

afterAll(async () => {
  await app.close();
  rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
});

const post = (url: string, payload: unknown, t = token) =>
  app.inject({ method: 'POST', url, headers: bearer(t), payload: payload as object });
const get = (url: string, t = token) => app.inject({ method: 'GET', url, headers: bearer(t) });
const put = (url: string, payload: unknown, t = token) =>
  app.inject({ method: 'PUT', url, headers: bearer(t), payload: payload as object });

describe('progress', () => {
  it('records a position with a server-assigned rev', async () => {
    const res = await put(`/api/v1/progress/${bookId}`, {
      fileId,
      targetKind: 'char',
      targetValue: '1200',
      percent: 12.5,
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().rev).toBeGreaterThan(0);
    expect(res.json().state.progress_percent).toBe(12.5);
    expect(res.json().state.status).toBe('reading');
  });

  it('keeps two devices in separate positions instead of overwriting each other', async () => {
    await put(`/api/v1/progress/${bookId}`, { fileId, targetKind: 'char', targetValue: '5000', percent: 40 }, otherDeviceToken);
    const positions = db.all<{ device_id: string; target_value: string }>(
      'SELECT device_id, target_value FROM reading_positions WHERE book_id = ? ORDER BY target_value',
      [bookId],
    );
    expect(positions.length).toBeGreaterThanOrEqual(2);
    expect(new Set(positions.map((p) => p.device_id)).size).toBeGreaterThanOrEqual(2);
  });

  it('marks a book finished at the end rather than leaving it perpetually reading', async () => {
    const res = await put(`/api/v1/progress/${bookId}`, {
      fileId,
      targetKind: 'percent',
      targetValue: '100',
      percent: 100,
    });
    expect(res.json().state.status).toBe('finished');
    expect(res.json().state.finished_at).toBeTruthy();
  });

  it('refuses a file id that belongs to a different book', async () => {
    const other = new BookRepo(db).upsertFromMetadata({
      meta: {
        format: 'txt', title: '另一本书', contributors: [], tags: [], cover: null,
        source: 'txt', confidence: 0.9, warnings: [], drm: false,
      } as never,
      sha256: 'f'.repeat(64), filename: '另一本书.txt', userId: null, mode: 'create',
    }).bookId;
    const res = await put(`/api/v1/progress/${other}`, {
      fileId,
      targetKind: 'char',
      targetValue: '1',
    });
    expect(res.statusCode).toBe(404);
    expect(res.json().error.code).toBe('file_not_found');
  });

  it('lists what the user is reading', async () => {
    const res = await get('/api/v1/progress?status=finished');
    expect(res.statusCode).toBe(200);
    expect((res.json().items as { book_id: string }[]).map((i) => i.book_id)).toContain(bookId);
  });
});

describe('bookmarks', () => {
  it('creates, updates and tombstones rather than hard-deleting', async () => {
    const created = await post('/api/v1/bookmarks', {
      bookId,
      fileId,
      targetKind: 'char',
      targetValue: '777',
      title: '第三章',
      note: '记一笔',
    });
    expect(created.statusCode).toBe(201);
    const id = created.json().bookmark.id as string;

    const deleted = await app.inject({
      method: 'DELETE',
      url: `/api/v1/bookmarks/${id}`,
      headers: bearer(token),
    });
    expect(deleted.statusCode).toBe(204);

    // Still present, with deleted_at set: an offline device has to be told about the removal.
    const rows = await get(`/api/v1/bookmarks?bookId=${bookId}`);
    const row = (rows.json().items as { id: string; deleted_at: string | null }[]).find((b) => b.id === id);
    expect(row).toBeDefined();
    expect(row?.deleted_at).not.toBeNull();
  });

  it('returns only changes after a given rev', async () => {
    const all = await get(`/api/v1/bookmarks?bookId=${bookId}`);
    const revs = (all.json().items as { rev: number }[]).map((b) => b.rev);
    const middle = Math.min(...revs);
    const since = await get(`/api/v1/bookmarks?bookId=${bookId}&since=${middle}`);
    expect((since.json().items as { rev: number }[]).every((b) => b.rev > middle)).toBe(true);
  });
});

describe('sync', () => {
  it('hands back everything after the cursor and advances it', async () => {
    const first = await get('/api/v1/sync/delta?cursor=0');
    expect(first.statusCode).toBe(200);
    const body = first.json();
    expect(Array.isArray(body.items)).toBe(true);
    expect(body.nextCursor).toBeGreaterThan(0);

    const again = await get(`/api/v1/sync/delta?cursor=${body.nextCursor}`);
    // Nothing new since, so the cursor must not move backwards or repeat rows.
    expect(again.json().items).toEqual([]);
    expect(again.json().nextCursor).toBe(body.nextCursor);
  });

  it('applies an offline change and assigns a fresh rev', async () => {
    const res = await post('/api/v1/sync/push', {
      changes: [
        {
          kind: 'state',
          bookId,
          payload: { status: 'reading', percent: 55, position: { kind: 'char', value: '6000' } },
        },
      ],
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().applied).toHaveLength(1);
    expect(res.json().applied[0].rev).toBeGreaterThan(0);

    const state = db.get<{ progress_percent: number }>(
      'SELECT progress_percent FROM user_book_states WHERE book_id = ?',
      [bookId],
    );
    expect(state?.progress_percent).toBe(55);
  });

  it('reports a stale push as a conflict rather than discarding the newer position', async () => {
    const current = db.get<{ rev: number }>(
      'SELECT rev FROM user_book_states WHERE book_id = ?',
      [bookId],
    )!.rev;
    const res = await post('/api/v1/sync/push', {
      changes: [
        {
          kind: 'state',
          bookId,
          baseRev: Math.max(0, current - 5),
          payload: { percent: 3 },
        },
      ],
    });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.applied).toHaveLength(0);
    expect(body.conflicts).toHaveLength(1);
    expect(body.conflicts[0].serverRev).toBe(current);
    // The client's stale value must not have landed.
    const state = db.get<{ progress_percent: number }>(
      'SELECT progress_percent FROM user_book_states WHERE book_id = ?',
      [bookId],
    );
    expect(state?.progress_percent).toBe(55);
  });

  it('accepts an offline bookmark delete pushed as a tombstone', async () => {
    const created = await post('/api/v1/bookmarks', {
      bookId,
      targetKind: 'char',
      targetValue: '900',
      title: '离线添加',
    });
    const id = created.json().bookmark.id as string;

    const pushed = await post('/api/v1/sync/push', {
      changes: [{ kind: 'bookmark', id, payload: { bookId, targetKind: 'char', targetValue: '900', deleted: true } }],
    });
    expect(pushed.statusCode).toBe(200);
    const row = db.get<{ deleted_at: string | null }>('SELECT deleted_at FROM bookmarks WHERE id = ?', [id]);
    expect(row?.deleted_at).not.toBeNull();
  });

  it('builds an offline manifest with one download entry per file', async () => {
    const res = await get('/api/v1/sync/offline-manifest');
    expect(res.statusCode).toBe(200);
    const body = res.json() as { books: { id: string; files: { id: string; sha256: string }[] }[] };
    const entry = body.books.find((b) => b.id === bookId);
    expect(entry).toBeDefined();
    expect(entry!.files.some((f) => f.id === fileId && f.sha256 === 'e'.repeat(64))).toBe(true);
  });

  it('scopes every sync read to the calling user', async () => {
    const users = new UserRepo(db);
    await seedUser(users, { username: 'stranger', password: 'stranger-password-1' });
    const strangerToken = (await signIn(app, 'stranger', 'stranger-password-1')).accessToken;
    const res = await get('/api/v1/sync/delta?cursor=0', strangerToken);
    expect(res.json().items).toEqual([]);
  });
});
