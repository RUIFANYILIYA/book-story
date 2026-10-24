import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { loadConfig } from '../src/config.ts';
import { buildApp } from '../src/app.ts';
import { openMigratedDb } from './helpers/db.ts';
import { UserRepo } from '../src/db/repositories/user.repo.ts';
import { BookRepo } from '../src/db/repositories/book.repo.ts';
import { bearer, seedUser, signIn } from './helpers/app.ts';

/**
 * The reader's own cover — the write side of a resource whose read side is four milestones old.
 *
 * `GET /covers/:bookId` already preferred `user_book_states.cover_override_blob_id`, so the property
 * that decides whether this feature is real is not "can a picture be stored" but **whose picture
 * arrives for whom**: one user's cover must not become another user's cover, and the shared record
 * must survive both. The rest of the file is the smaller promises — a non-image is refused, a
 * 40 MB screenshot is refused, and a cover change does not disturb the reading state riding in the
 * same row.
 */

const BOUNDARY = '----ebookdCoverTest';

/** Magic bytes are what the server checks, so the fixture is honest about that rather than pretty. */
const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00]);
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d]);
const NOT_AN_IMAGE = Buffer.from('this is a text file pretending very hard', 'utf8');

function multipartImage(bytes: Buffer, filename = 'cover.jpg'): Buffer {
  const head = Buffer.from(
    `--${BOUNDARY}\r\n` +
      'Content-Disposition: form-data; name="cover"; filename="' +
      filename +
      '"\r\n' +
      'Content-Type: application/octet-stream\r\n\r\n',
    'utf8',
  );
  const tail = Buffer.from(`\r\n--${BOUNDARY}--\r\n`, 'utf8');
  return Buffer.concat([head, bytes, tail]);
}

let app: FastifyInstance;
let dir: string;
let db: ReturnType<typeof openMigratedDb>;
let ownerToken: string;
let otherToken: string;
let ownerId: string;
let bookId: string;

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-cover-'));
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
  ownerId = await seedUser(users, { username: 'owner', password: 'owner-password-1' });
  await seedUser(users, { username: 'other', password: 'other-password-1' });
  ownerToken = (await signIn(app, 'owner', 'owner-password-1')).accessToken;
  otherToken = (await signIn(app, 'other', 'other-password-1')).accessToken;

  bookId = new BookRepo(db).upsertFromMetadata({
    meta: {
      format: 'txt',
      title: '封面测试书',
      contributors: [],
      tags: [],
      cover: null,
      source: 'txt',
      confidence: 0.9,
      warnings: [],
      drm: false,
    } as never,
    sha256: 'a'.repeat(64),
    filename: '封面测试书.txt',
    userId: ownerId,
    mode: 'create',
  }).bookId;
});

afterAll(async () => {
  await app.close();
  rmSync(dir, { recursive: true, force: true });
});

const upload = (body: Buffer, token: string | null = ownerToken) =>
  app.inject({
    method: 'POST',
    url: `/api/v1/books/${bookId}/cover`,
    headers: {
      'content-type': `multipart/form-data; boundary=${BOUNDARY}`,
      ...(token === null ? {} : bearer(token)),
    },
    payload: body,
  });

const cover = (token: string) => app.inject({ method: 'GET', url: `/api/v1/covers/${bookId}`, headers: bearer(token) });
const state = (userId: string) =>
  db.get<{
    status: string;
    shelf: string;
    progress_percent: number | null;
    rev: number;
    cover_override_blob_id: string | null;
  }>('SELECT status, shelf, progress_percent, rev, cover_override_blob_id FROM user_book_states WHERE user_id = ? AND book_id = ?', [
    userId,
    bookId,
  ]);
const refcount = (sha256: string) =>
  db.get<{ refcount: number }>('SELECT refcount FROM blobs WHERE sha256 = ?', [sha256])?.refcount;
const blobCount = () => Number(db.all<{ n: number }>('SELECT count(*) AS n FROM blobs')[0]?.n ?? -1);

describe('POST /books/:bookId/cover', () => {
  it('refuses an anonymous request', async () => {
    const res = await upload(multipartImage(JPEG), null);
    expect(res.statusCode).toBe(401);
  });

  it('refuses a body that is not multipart', async () => {
    const res = await app.inject({
      method: 'POST',
      url: `/api/v1/books/${bookId}/cover`,
      headers: bearer(ownerToken),
      payload: { nope: true },
    });
    expect(res.statusCode).toBe(415);
  });

  it('refuses bytes that are not a picture, and stores nothing', async () => {
    const before = blobCount();
    const res = await upload(multipartImage(NOT_AN_IMAGE));
    expect(res.statusCode).toBe(415);
    expect(res.json().error.code).toBe('unsupported_media_type');
    expect(blobCount()).toBe(before);
  });

  it('refuses a cover over the picture limit rather than buffering it', async () => {
    // 12 MiB is the cap; the multipart plugin's own limit is the book limit, which is far larger.
    const huge = Buffer.concat([JPEG, Buffer.alloc(13 * 1024 * 1024, 0x41)]);
    const res = await upload(multipartImage(huge));
    expect(res.statusCode).toBe(413);
    // The code and the number are this route's, which is what proves the cap fired here rather than
    // in the plugin's generic error mapping.
    expect(res.json().error.code).toBe('payload_too_large');
    expect(res.json().error.message).toContain('12582912');
  });

  it('serves the uploaded bytes to the reader who uploaded them', async () => {
    const res = await upload(multipartImage(JPEG));
    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.coverUrl).toBe(`/api/v1/covers/${bookId}`);
    expect(body.mediaType).toBe('image/jpeg');
    expect(body.sizeBytes).toBe(JPEG.length);

    const served = await cover(ownerToken);
    expect(served.statusCode).toBe(200);
    expect(served.rawPayload.equals(JPEG)).toBe(true);
    // The ETag is the blob's content hash, which is what makes a new cover a new validator
    // automatically — and re-uploading the same bytes correctly answers 304.
    expect(served.headers['etag']).toBe(`"${createHash('sha256').update(JPEG).digest('hex').slice(0, 32)}"`);
    // `private`, because the same URL answers differently per user and an intermediary must not mix them.
    expect(served.headers['cache-control']).toContain('private');
  });

  it('does not show one reader their neighbour’s cover', async () => {
    const other = await cover(otherToken);
    // The book has no shared cover, so the only cover in the library belongs to `owner` — and `other`
    // must not see it.
    expect(other.statusCode).toBe(404);
  });

  it('gives each reader their own override on the same book', async () => {
    await upload(multipartImage(PNG, 'cover.png'), otherToken);
    const ownerView = await cover(ownerToken);
    const otherView = await cover(otherToken);
    expect(ownerView.rawPayload.equals(JPEG)).toBe(true);
    expect(otherView.rawPayload.equals(PNG)).toBe(true);
  });

  it('leaves the reading state in the same row alone', async () => {
    const marked = await app.inject({
      method: 'POST',
      url: '/api/v1/books/batch/status',
      headers: { 'content-type': 'application/json', ...bearer(ownerToken) },
      payload: { bookIds: [bookId], status: 'finished' },
    });
    expect(marked.statusCode).toBe(200);
    const before = state(ownerId);
    expect(before?.status).toBe('finished');

    const res = await upload(multipartImage(JPEG));
    expect(res.statusCode).toBe(201);
    const after = state(ownerId);
    expect(after?.status).toBe('finished');
    expect(after?.shelf).toBe(before?.shelf);
    expect(after?.rev).toBeGreaterThan(before?.rev ?? 0);
  });

  it('hands a cover-only revision to the delta as the same position, not a new one', async () => {
    const before = state(ownerId);
    const res = await upload(multipartImage(PNG, 'again.png'));
    expect(res.statusCode).toBe(201);
    const delta = await app.inject({
      method: 'GET',
      url: `/api/v1/sync/delta?cursor=${(before?.rev ?? 1) - 1}`,
      headers: bearer(ownerToken),
    });
    const items = delta.json().items.filter((i: { book_id: string }) => i.book_id === bookId);
    const newest = items.at(-1);
    expect(newest.rev).toBe(state(ownerId)?.rev);
    // Whatever the row's percentage was, a cover write must not have invented one.
    expect(newest.progress_percent).toBe(state(ownerId)?.progress_percent ?? null);
  });
});

describe('DELETE /books/:bookId/cover', () => {
  it('releases one reference for the cover it replaces, and one for the cover it clears', async () => {
    const first = await upload(multipartImage(JPEG));
    expect(first.statusCode).toBe(201);
    const firstSha = first.json().sha256 as string;
    // Refcounts are counted, not boolean: these bytes have been uploaded by earlier cases in this
    // file, so the assertion is about the delta one replacement makes, not about an absolute number.
    const firstBefore = refcount(firstSha);

    const second = await upload(multipartImage(PNG, 'second.png'));
    expect(second.statusCode).toBe(201);
    const secondSha = second.json().sha256 as string;
    expect(secondSha).not.toBe(firstSha);
    expect(refcount(firstSha)).toBe((firstBefore ?? 0) - 1);

    // Identical bytes dedup to one blob, so re-uploading the current cover must not release it.
    const same = await upload(multipartImage(PNG, 'same.png'));
    expect(same.json().sha256).toBe(secondSha);
    const secondBefore = refcount(secondSha);
    expect(secondBefore).toBeGreaterThan(0);

    const cleared = await app.inject({
      method: 'DELETE',
      url: `/api/v1/books/${bookId}/cover`,
      headers: bearer(ownerToken),
    });
    expect(cleared.statusCode).toBe(204);
    expect(state(ownerId)?.cover_override_blob_id).toBeNull();
    expect(refcount(secondSha)).toBe((secondBefore ?? 0) - 1);
    expect((await cover(ownerToken)).statusCode).toBe(404);
  });

  it('is 204 when there was nothing to clear', async () => {
    const res = await app.inject({
      method: 'DELETE',
      url: `/api/v1/books/${bookId}/cover`,
      headers: bearer(ownerToken),
    });
    expect(res.statusCode).toBe(204);
  });

  it('404s for a book that is not there', async () => {
    // Well-formed and absent: a malformed id is a 422 from the schema, which is a different promise.
    const missing = await app.inject({
      method: 'POST',
      url: '/api/v1/books/01ZZZZZZZZZZZZZZZZZZZZZZZZ/cover',
      headers: { 'content-type': `multipart/form-data; boundary=${BOUNDARY}`, ...bearer(ownerToken) },
      payload: multipartImage(JPEG),
    });
    expect(missing.statusCode).toBe(404);
    expect(missing.json().error.code).toBe('book_not_found');
  });
});

describe('the bytes on disk', () => {
  it('are stored once per distinct content, under the cover shard', async () => {
    const res = await upload(multipartImage(JPEG));
    const sha = res.json().sha256 as string;
    const path = db.get<{ storage_path: string }>('SELECT storage_path FROM blobs WHERE sha256 = ?', [sha])
      ?.storage_path;
    expect(path).toBeDefined();
    const stored = readFileSync(join(dir, 'library', path!));
    expect(stored.equals(JPEG)).toBe(true);
    expect(refcount(sha)).toBeGreaterThanOrEqual(1);
  });
});
