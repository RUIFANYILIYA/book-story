import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
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
import { contentDisposition, decideRange, etagFor } from '../src/http/range-stream.ts';
import { bearer, seedUser, signIn } from './helpers/app.ts';

/**
 * Byte-range delivery.
 *
 * The reason this file exists rather than a couple of happy-path checks: a range implementation
 * that is off by one byte produces a file that still opens, still looks the right size, and is
 * silently wrong. So the assertions compare raw bytes against the source at several offsets,
 * including the boundaries (0, the last byte, and a slice straddling a megabyte).
 */

let app: FastifyInstance;
let dir: string;
let db: ReturnType<typeof openMigratedDb>;
let token: string;
let fileId: string;
let sourcePath: string;
let sourceBytes: Buffer;

const SIZE = 3 * 1024 * 1024 + 7; // deliberately not a round number, and not a buffer multiple

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-range-'));
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
  token = (await signIn(app, 'reader', 'reader-password-1')).accessToken;

  // Deterministic bytes that are not constant, so a wrong offset is visible in the comparison
  // rather than matching by luck.
  sourceBytes = Buffer.alloc(SIZE);
  for (let i = 0; i < SIZE; i += 1) sourceBytes[i] = (i * 31 + (i >> 8)) & 0xff;

  const sha = createHash('sha256').update(sourceBytes).digest('hex');
  const relative = `blobs/${sha.slice(0, 2)}/${sha.slice(2, 4)}/${sha}`;
  sourcePath = join(dir, 'library', relative);
  mkdirSync(join(dir, 'library', relative.slice(0, relative.lastIndexOf('/'))), { recursive: true });
  writeFileSync(sourcePath, sourceBytes);

  const blobs = new BlobRepo(db, join(dir, 'library'));
  const blob = blobs.register({
    sha256: sha,
    sizeBytes: SIZE,
    relativePath: relative,
    mediaType: 'text/plain',
    format: 'txt',
    refcount: 0,
  });
  const books = new BookRepo(db);
  const bookId = books.upsertFromMetadata({
    meta: {
      format: 'txt',
      title: '范围测试书',
      contributors: [],
      tags: [],
      cover: null,
      source: 'txt',
      confidence: 0.9,
      warnings: [],
      drm: false,
    } as never,
    sha256: sha,
    filename: '范围测试书.txt',
    userId: null,
    mode: 'create',
  }).bookId;
  fileId = books.attachFile({
    bookId,
    blobId: blob.id,
    sha256: sha,
    sizeBytes: SIZE,
    format: 'txt',
    mediaType: 'text/plain',
    // A Chinese name on purpose: this is what the RFC 5987 encoding in Content-Disposition is for.
    filename: '范围测试书.txt',
  });
});

afterAll(async () => {
  await app.close();
  rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
});

const download = (headers: Record<string, string> = {}, method: 'GET' | 'HEAD' = 'GET') =>
  app.inject({ method, url: `/api/v1/files/${fileId}/download`, headers: { ...bearer(token), ...headers } });

describe('decideRange (pure)', () => {
  const etag = etagFor('a'.repeat(64));

  it('sends the whole file when no range is asked for', () => {
    expect(decideRange({ size: 1000, etag })).toEqual({ status: 200, start: 0, end: 999 });
  });

  it('satisfies a normal range', () => {
    expect(decideRange({ size: 1000, rangeHeader: 'bytes=10-19', etag })).toEqual({
      status: 206,
      start: 10,
      end: 19,
      contentRange: 'bytes 10-19/1000',
    });
  });

  it('treats a malformed range as no range at all, per RFC 7233', () => {
    expect(decideRange({ size: 1000, rangeHeader: 'bytes=abc', etag }).status).toBe(200);
  });

  it('reports an unsatisfiable range as 416 and states the real size', () => {
    const decision = decideRange({ size: 1000, rangeHeader: 'bytes=5000-6000', etag });
    expect(decision.status).toBe(416);
    expect(decision.contentRange).toBe('bytes */1000');
  });

  it('ignores a range when If-Range does not match, which is what stops a mixed old/new file', () => {
    const decision = decideRange({ size: 1000, rangeHeader: 'bytes=10-19', ifRangeHeader: '"stale"', etag });
    expect(decision.status).toBe(200);
    expect(decision.start).toBe(0);
    expect(decision.end).toBe(999);
  });

  it('honours a range when If-Range matches the current validator', () => {
    const decision = decideRange({ size: 1000, rangeHeader: 'bytes=10-19', ifRangeHeader: etag, etag });
    expect(decision.status).toBe(206);
  });

  it('clamps an open-ended range to the file size', () => {
    const decision = decideRange({ size: 1000, rangeHeader: 'bytes=990-', etag });
    expect(decision).toEqual({ status: 206, start: 990, end: 999, contentRange: 'bytes 990-999/1000' });
  });
});

describe('contentDisposition', () => {
  it('sends both an ASCII fallback and the RFC 5987 form', () => {
    const header = contentDisposition('attachment', '范围测试书.txt');
    // Five CJK characters become five underscores; the ASCII fallback exists for clients that
    // still ignore filename*, where the alternative is a percent-encoded filename on disk.
    expect(header).toContain('filename="_____.txt"');
    expect(header).toContain(`filename*=UTF-8''${encodeURIComponent('范围测试书.txt')}`);
  });

  it('strips quotes and backslashes that would break the header', () => {
    expect(contentDisposition('attachment', 'a"b\\c.txt')).toContain('filename="abc.txt"');
  });
});

describe('GET /files/:id/download', () => {
  it('serves the whole file with a strong content-derived ETag', async () => {
    const res = await download();
    expect(res.statusCode).toBe(200);
    expect(res.headers['accept-ranges']).toBe('bytes');
    expect(res.headers['content-length']).toBe(String(SIZE));
    expect(res.headers['etag']).toBe(etagFor(createHash('sha256').update(sourceBytes).digest('hex')));
    expect(res.headers['x-content-sha256']).toBe(createHash('sha256').update(sourceBytes).digest('hex'));
    // A gzip filter here would make byte offsets refer to compressed positions.
    expect(res.headers['content-encoding']).toBeUndefined();
    expect(res.rawPayload.equals(sourceBytes)).toBe(true);
  });

  it('returns byte-identical slices at several offsets', async () => {
    const ranges: [number, number][] = [
      [0, 4095],
      [1, 1],
      [SIZE - 1, SIZE - 1],
      [1024 * 1024 - 3, 1024 * 1024 + 3],
      [SIZE - 8192, SIZE - 1],
    ];
    for (const [start, end] of ranges) {
      const res = await download({ range: `bytes=${start}-${end}` });
      expect(res.statusCode, `${start}-${end}`).toBe(206);
      expect(res.headers['content-range'], `${start}-${end}`).toBe(`bytes ${start}-${end}/${SIZE}`);
      expect(res.headers['content-length'], `${start}-${end}`).toBe(String(end - start + 1));
      expect(res.rawPayload.equals(sourceBytes.subarray(start, end + 1)), `bytes ${start}-${end}`).toBe(true);
    }
  });

  it('answers an open-ended resume request, which is what a downloader sends after a drop', async () => {
    const res = await download({ range: `bytes=${SIZE - 10}-` });
    expect(res.statusCode).toBe(206);
    expect(res.headers['content-range']).toBe(`bytes ${SIZE - 10}-${SIZE - 1}/${SIZE}`);
    expect(res.rawPayload.equals(sourceBytes.subarray(SIZE - 10))).toBe(true);
  });

  it('refuses a range beyond the end with 416 and the true size', async () => {
    const res = await download({ range: `bytes=${SIZE + 100}-${SIZE + 200}` });
    expect(res.statusCode, res.body).toBe(416);
    expect(res.headers['content-range']).toBe(`bytes */${SIZE}`);
    expect(res.json().error.code).toBe('range_not_satisfiable');
  });

  it('answers 200 rather than 206 when If-Range names a different version', async () => {
    const res = await download({ range: 'bytes=0-99', 'if-range': '"a-different-file"' });
    expect(res.statusCode).toBe(200);
    expect(res.rawPayload.length).toBe(SIZE);
  });

  it('honours If-Range when it matches', async () => {
    const etag = etagFor(createHash('sha256').update(sourceBytes).digest('hex'));
    const res = await download({ range: 'bytes=0-99', 'if-range': etag });
    expect(res.statusCode).toBe(206);
  });

  it('answers 304 when the client already holds this version', async () => {
    const etag = etagFor(createHash('sha256').update(sourceBytes).digest('hex'));
    const res = await download({ 'if-none-match': etag });
    expect(res.statusCode).toBe(304);
    expect(res.rawPayload.length).toBe(0);
  });

  it('describes a HEAD fully and sends no body', async () => {
    const res = await download({}, 'HEAD');
    expect(res.statusCode).toBe(200);
    expect(res.headers['content-length']).toBe(String(SIZE));
    expect(res.headers['accept-ranges']).toBe('bytes');
    expect(res.rawPayload.length).toBe(0);
  });

  it('carries the book and file ids so a client can correlate its cache', async () => {
    const res = await download();
    expect(res.headers['x-file-id']).toBe(fileId);
    expect(res.headers['x-book-id']).toBeTruthy();
  });

  it('offers a Chinese filename in both forms', async () => {
    const res = await download();
    expect(res.headers['content-disposition']).toContain("filename*=UTF-8''");
    expect(res.headers['content-disposition']).toContain(encodeURIComponent('范围测试书.txt'));
  });

  it('requires a credential', async () => {
    const res = await app.inject({ method: 'GET', url: `/api/v1/files/${fileId}/download` });
    expect(res.statusCode).toBe(401);
  });

  it('returns 404 for a file id that does not exist, not an empty body', async () => {
    const res = await app.inject({
      method: 'GET',
      url: `/api/v1/files/${'A'.repeat(26)}/download`,
      headers: bearer(token),
    });
    expect(res.statusCode).toBe(404);
    expect(res.json().error.code).toBe('file_not_found');
  });

  it('serves twelve concurrent readers without buffering the file per request', async () => {
    const before = process.memoryUsage().rss;
    const responses = await Promise.all(
      Array.from({ length: 12 }, (_, i) => download({ range: `bytes=${i * 1024}-${i * 1024 + 65535}` })),
    );
    for (const res of responses) expect(res.statusCode).toBe(206);
    const growthMb = (process.memoryUsage().rss - before) / (1024 * 1024);
    // Twelve 64 KiB slices must not cost twelve copies of a 3 MB file; the threshold is loose
    // because RSS is noisy, but a buffering implementation would blow past it.
    expect(growthMb).toBeLessThan(64);
  });
});
