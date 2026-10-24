import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { loadConfig } from '../src/config.ts';
import { buildApp } from '../src/app.ts';
import { openMigratedDb } from './helpers/db.ts';
import type { Db } from '../src/db/index.ts';
import { UserRepo } from '../src/db/repositories/user.repo.ts';
import { bearer, seedUser, signIn } from './helpers/app.ts';

/**
 * `DELETE /api/v1/books/:id`.
 *
 * The load-bearing cases are the ones a console actually produces: a real upload, a real delete,
 * and then the questions the answer has to survive — is it gone from the shelf, is the file still
 * downloadable for the retention window, does a second delete admit the book was already gone,
 * and does a caller without `book:delete` get refused before anything is written.
 */

let app: FastifyInstance;
let dir: string;
let db: Db;
let adminToken: string;
let readerToken: string;

const BOUNDARY = '----ebookdTestBoundary7MA4YWxkTrZu0gW';

function multipart(file: { name: string; filename: string; body: string }): Buffer {
  return Buffer.from(
    `--${BOUNDARY}\r\nContent-Disposition: form-data; name="${file.name}"; filename="${file.filename}"\r\n` +
      'Content-Type: application/octet-stream\r\n\r\n' +
      `${file.body}\r\n` +
      `--${BOUNDARY}--\r\n`,
    'utf8',
  );
}

function upload(token: string, filename: string, body: string) {
  return app.inject({
    method: 'POST',
    url: '/api/v1/uploads',
    headers: { 'content-type': `multipart/form-data; boundary=${BOUNDARY}`, ...bearer(token) },
    payload: multipart({ name: 'file', filename, body }),
  });
}

/** A book on the shelf with real bytes behind it, so the retention window can be observed. */
async function seedBook(filename: string, body: string): Promise<{ bookId: string; fileId: string }> {
  const res = await upload(adminToken, filename, body);
  expect(res.statusCode).toBe(201);
  const created = res.json();
  return { bookId: created.bookId, fileId: created.fileId };
}

const remove = (bookId: string, token: string = adminToken) =>
  app.inject({ method: 'DELETE', url: `/api/v1/books/${bookId}`, headers: bearer(token) });

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-delete-'));
  db = openMigratedDb();
  app = await buildApp({
    config: loadConfig({
      NODE_ENV: 'test',
      EBOOK_LOG_LEVEL: 'silent',
      EBOOK_DATA_DIR: join(dir, 'db'),
      EBOOK_LIBRARY_DIR: join(dir, 'library'),
      EBOOK_JWT_SECRET: 'test-secret-test-secret-test-secret-1234567890',
    }),
    db,
  });
  await app.ready();

  const users = new UserRepo(db);
  // The default user role carries only library:read and file:upload, which is exactly the caller
  // that must be refused here.
  await seedUser(users, { username: 'curator', password: 'curator-password-1', role: 'admin' });
  await seedUser(users, { username: 'reader', password: 'reader-password-1' });
  adminToken = (await signIn(app, 'curator', 'curator-password-1')).accessToken;
  readerToken = (await signIn(app, 'reader', 'reader-password-1')).accessToken;
});

afterAll(async () => {
  await app.close();
  rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
});

describe('DELETE /api/v1/books/:id', () => {
  it('tombstones the book: gone from the shelf and from the detail route', async () => {
    const { bookId } = await seedBook('待删除 - 甲.txt', '这本书下一秒就不在架上了。');

    const res = await remove(bookId);
    expect(res.statusCode).toBe(204);

    const list = await app.inject({ url: '/api/v1/books', headers: bearer(adminToken) });
    expect(list.json().items.map((b: { id: string }) => b.id)).not.toContain(bookId);

    const detail = await app.inject({ url: `/api/v1/books/${bookId}`, headers: bearer(adminToken) });
    expect(detail.statusCode).toBe(404);
    expect(detail.json().error.code).toBe('book_not_found');
  });

  it('keeps the file downloadable for the retention window', async () => {
    // The whole point of a soft delete: another device's half-finished download must not be
    // corrupted by the delete, so the bytes outlive the listing by the retention window.
    const { bookId, fileId } = await seedBook('保留期 - 乙.txt', '删除之后字节还在原处。');
    expect((await remove(bookId)).statusCode).toBe(204);

    const file = await app.inject({
      url: `/api/v1/files/${fileId}/download`,
      headers: bearer(adminToken),
    });
    expect(file.statusCode).toBe(200);
    expect(file.body).toContain('删除之后字节还在原处。');
  });

  it('answers 404 on a second delete rather than an idempotent 204', async () => {
    // "It was there and now is not" and "it was never there" are different answers; collapsing
    // them would let a stale console tab believe it had just deleted something.
    const { bookId } = await seedBook('重复删除 - 丙.txt', '删一次就没了。');
    expect((await remove(bookId)).statusCode).toBe(204);

    const again = await remove(bookId);
    expect(again.statusCode).toBe(404);
    expect(again.json().error.code).toBe('book_not_found');
  });

  it('refuses a caller without book:delete before writing anything', async () => {
    const { bookId } = await seedBook('权限 - 丁.txt', '这个人没有删除权限。');

    const res = await remove(bookId, readerToken);
    expect(res.statusCode).toBe(403);
    expect(res.json().error.code).toBe('capability_denied');

    // The refusal must have left the book exactly as it was.
    const detail = await app.inject({ url: `/api/v1/books/${bookId}`, headers: bearer(adminToken) });
    expect(detail.statusCode).toBe(200);
  });

  it('refuses an anonymous caller', async () => {
    const { bookId } = await seedBook('匿名 - 戊.txt', '没有令牌不应该删掉任何东西。');
    const res = await remove(bookId, '');
    expect(res.statusCode).toBe(401);
  });

  it('leaves the batch delete route working alongside it', async () => {
    const a = await seedBook('批量 - 己.txt', '第一本。');
    const b = await seedBook('批量 - 庚.txt', '第二本。');
    const survivor = await seedBook('批量 - 辛.txt', '第三本，留着。');

    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/books/batch/delete',
      headers: bearer(adminToken),
      payload: { bookIds: [a.bookId, b.bookId] },
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().applied).toBe(2);

    const list = await app.inject({ url: '/api/v1/books', headers: bearer(adminToken) });
    const ids = list.json().items.map((book: { id: string }) => book.id);
    expect(ids).not.toContain(a.bookId);
    expect(ids).not.toContain(b.bookId);
    expect(ids).toContain(survivor.bookId);
  });
});
