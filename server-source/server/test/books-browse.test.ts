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
 * Browse, search and pagination.
 *
 * The load-bearing case here is that walking every page returns every book exactly once. A keyset
 * cursor that ties incorrectly drops or repeats a row at a page boundary, and because the bug only
 * shows when two rows share a sort value it survives most hand-testing.
 */

let app: FastifyInstance;
let dir: string;
let token: string;
let db: ReturnType<typeof openMigratedDb>;

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-browse-'));
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
  await seedUser(users, { username: 'browser', password: 'browse-password-1' });
  token = (await signIn(app, 'browser', 'browse-password-1')).accessToken;

  // Seed: 25 books sharing one author, so many rows tie on every sort and the keyset tiebreak
  // actually gets exercised. Years and languages vary so the filters have something to separate.
  const books = new BookRepo(db);
  const blobs = new BlobRepo(db, join(dir, 'library'));
  const uploaderId = users.byUsername('browser')!.id;
  for (let i = 0; i < 25; i += 1) {
    const title = `测试书目 ${String(i).padStart(2, '0')}`;
    const sha = i.toString(16).padStart(2, '0') + 'a'.repeat(62);
    const blob = blobs.register({
      sha256: sha,
      sizeBytes: 1000 + i,
      relativePath: `blobs/${sha.slice(0, 2)}/${sha.slice(2, 4)}/${sha}`,
      mediaType: 'text/plain',
      format: 'txt',
      refcount: 0,
    });
    const id = books.upsertFromMetadata({
      meta: {
        format: 'txt',
        title,
        contributors: [{ name: '共同作者', role: 'author' }],
        tags: i % 3 === 0 ? ['科幻'] : ['随笔'],
        publishedYear: 1990 + (i % 7),
        language: i % 5 === 0 ? 'en' : 'zh-CN',
        cover: null,
        source: 'epub',
        confidence: 0.95,
        warnings: [],
        drm: false,
      } as never,
      sha256: sha,
      filename: `${title}.txt`,
      userId: uploaderId,
      mode: 'create',
    }).bookId;
    books.attachFile({
      bookId: id,
      blobId: blob.id,
      sha256: sha,
      sizeBytes: 1000 + i,
      format: 'txt',
      mediaType: 'text/plain',
      filename: `${title}.txt`,
    });
    books.refreshSearchText(id);
  }
});

afterAll(async () => {
  await app.close();
  rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
});

interface BrowseBody {
  items: Record<string, unknown>[];
  nextCursor: string | null;
  hasMore: boolean;
  limit: number;
  sort: string;
  order: string;
  error?: { code: string; message: string };
}

async function browse(query = '') {
  const res = await app.inject({ method: 'GET', url: `/api/v1/books${query}`, headers: bearer(token) });
  return { status: res.statusCode, body: res.json() as BrowseBody };
}

describe('GET /api/v1/books', () => {
  it('requires a credential', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/v1/books' });
    expect(res.statusCode).toBe(401);
  });

  it('returns the summary shape the app renders without a second request', async () => {
    const { status, body } = await browse('?limit=3');
    expect(status).toBe(200);
    const item = body.items[0] as Record<string, unknown>;
    expect(Object.keys(item).sort()).toEqual([
      'authors',
      'categoryId',
      'contentUpdatedAt',
      'coverUpdatedAt',
      'coverUrl',
      'fileCount',
      'formats',
      'hasCover',
      'id',
      'language',
      'my',
      'needsReview',
      'seriesIndex',
      'seriesName',
      'sizeBytes',
      'title',
      'updatedAt',
      'year',
    ]);
    expect(item.authors).toEqual(['共同作者']);
  });

  it('walks every page and sees each book exactly once', async () => {
    const seen: string[] = [];
    let cursor: string | null = null;
    for (let page = 0; page < 40; page += 1) {
      const { status, body } = await browse(`?limit=4&sort=title&order=asc${cursor ? `&cursor=${cursor}` : ''}`);
      if (!body.items) {
        throw new Error(`page ${page} returned ${status}: ${JSON.stringify(body)}`);
      }
      seen.push(...body.items.map((i) => String(i.id)));
      cursor = body.nextCursor;
      if (!cursor) break;
    }
    expect(new Set(seen).size).toBe(seen.length);
    expect(seen.length).toBe(25);
  });

  it('refuses a cursor from a different sort instead of returning nonsense', async () => {
    const first = await browse('?limit=4&sort=title');
    const cursor = first.body.nextCursor as string;
    const res = await app.inject({
      method: 'GET',
      url: `/api/v1/books?limit=4&sort=year&cursor=${cursor}`,
      headers: bearer(token),
    });
    expect(res.statusCode).toBe(400);
    expect(res.json().error.code).toBe('bad_request');
  });

  it('rejects a forged cursor rather than interpolating it into SQL', async () => {
      const res = await app.inject({
      method: 'GET',
      url: '/api/v1/books?limit=4&cursor=Zm9yZ2Vk.c2ln',
      headers: bearer(token),
    });
    expect(res.statusCode).toBe(400);
    expect(res.json().error.code).toBe('bad_request');
  });

  it('answers a two-character CJK query through LIKE, which the trigram index cannot serve', async () => {
    const short = await browse('?q=测试');
    expect((short.body.items as unknown[]).length).toBeGreaterThan(0);

    const long = await browse('?q=测试书目');
    expect((long.body.items as unknown[]).length).toBeGreaterThan(0);
  });

  it('filters by tag, format and language independently', async () => {
    const tagged = await browse('?tag=科幻&limit=100');
    const untagged = await browse('?notTag=科幻&limit=100');
    const tagIds = new Set((tagged.body.items as { id: string }[]).map((i) => i.id));
    const untaggedIds = new Set((untagged.body.items as { id: string }[]).map((i) => i.id));
    expect(tagIds.size + untaggedIds.size).toBe(25);
    expect([...tagIds].some((id) => untaggedIds.has(id))).toBe(false);

    const en = await browse('?language=en&limit=100');
    expect((en.body.items as { language: string }[]).every((i) => i.language === 'en')).toBe(true);
  });

  it('rejects an unknown sort key instead of passing it to ORDER BY', async () => {
    const res = await app.inject({
      method: 'GET',
      url: "/api/v1/books?sort=id);DROP TABLE books;--",
      headers: bearer(token),
    });
    expect(res.statusCode).toBe(422);
    expect(res.json().error.code).toBe('validation_failed');
    // The table is still there to serve a valid request.
    expect((await browse('?limit=1')).body.items).toHaveLength(1);
  });

  it('answers every sort key it advertises, rather than 500ing on the ones nobody clicked', async () => {
    // `lastRead` and `progress` sorted on `s.*`, which is the *series* alias, so the Android shelf —
    // whose default sort is lastRead — got a 500 from an unjoined column. The only defence for a
    // table of eight sort keys is to ask the server for all eight.
    for (const sort of ['addedAt', 'title', 'author', 'year', 'lastRead', 'progress', 'size']) {
      const res = await app.inject({
        method: 'GET',
        url: `/api/v1/books?sort=${sort}&limit=100`,
        headers: bearer(token),
      });
      expect(res.statusCode, sort).toBe(200);
      expect(res.json().items, sort).toHaveLength(25);
    }
    // `shelf` is a column of a table that only a collection query joins, so it is refused cleanly
    // instead of reaching SQL.
    const shelf = await app.inject({ method: 'GET', url: '/api/v1/books?sort=shelf', headers: bearer(token) });
    expect(shelf.statusCode).toBe(422);
    expect(shelf.json().error.code).toBe('validation_failed');
  });

  it('escapes LIKE metacharacters rather than turning them into a wildcard', async () => {
    // `%` un-escaped would match every row, and normalizing the term to the empty string would do
    // the same thing by a different route. Either way a meaningless query returns the library.
    for (const term of ['%25', '%', '___']) {
      const res = await app.inject({
        method: 'GET',
        url: `/api/v1/books?q=${encodeURIComponent(term)}&limit=100`,
        headers: bearer(token),
      });
      expect(res.statusCode, term).toBe(200);
      expect(res.json().items, term).toEqual([]);
    }
  });
});

describe('GET /api/v1/books/:id', () => {
  /**
   * The detail response is built by spreading a SQLite row, and SQLite stores booleans as 0/1 — so
   * `needs_review: 1` was a valid answer this file never checked. The declared type is boolean, and
   * a client that parses strictly (the Android app's serializer) rejects `1` outright rather than
   * coercing it, which turns a cosmetic-looking leak into "the app cannot open a book".
   */
  it('answers the boolean columns as booleans, not as the integers SQLite stores', async () => {
    const { body } = await browse('?limit=1');
    const id = (body.items as { id: string }[])[0]!.id;
    const res = await app.inject({ method: 'GET', url: `/api/v1/books/${id}`, headers: bearer(token) });
    expect(res.statusCode).toBe(200);
    const detail = res.json() as { book: Record<string, unknown>; files: Record<string, unknown>[] };

    expect(typeof detail.book.needs_review).toBe('boolean');
    expect(typeof detail.book.has_cover).toBe('boolean');

    expect(detail.files.length).toBeGreaterThan(0);
    for (const file of detail.files) {
      expect(typeof file.is_primary).toBe('boolean');
      expect(typeof file.drm).toBe('boolean');
      expect(typeof file.size_bytes).toBe('number');
      expect(String(file.sha256)).toMatch(/^[0-9a-f]{64}$/u);
    }
  });
});

describe('PATCH /api/v1/books/:id', () => {
  it('refuses a normal user, since shared metadata needs library:edit', async () => {
    const { body } = await browse('?limit=1');
    const id = (body.items as { id: string }[])[0]!.id;
    const res = await app.inject({
      method: 'PATCH',
      url: `/api/v1/books/${id}`,
      headers: bearer(token),
      payload: { title: '不该被改掉的名字' },
    });
    expect(res.statusCode).toBe(403);
    expect(res.json().error.code).toBe('capability_denied');
  });

  it('marks manually edited fields as pinned so a re-parse will not undo them', async () => {
    const users = new UserRepo(db);
    await seedUser(users, { username: 'editor', password: 'editor-password-1', role: 'admin' });
    const adminToken = (await signIn(app, 'editor', 'editor-password-1')).accessToken;
    const { body } = await browse('?limit=1&sort=title&order=asc');
    const id = (body.items as { id: string }[])[0]!.id;

    const res = await app.inject({
      method: 'PATCH',
      url: `/api/v1/books/${id}`,
      headers: bearer(adminToken),
      payload: { title: '人工修正后的书名', tags: ['科幻', '手工'] },
    });
    expect(res.statusCode).toBe(200);
    const edited = res.json().book as Record<string, unknown>;
    expect(edited.title).toBe('人工修正后的书名');
    expect(JSON.parse(edited.pinned_fields_json as string)).toContain('title');
    expect((edited.tags as string[]).sort()).toEqual(['手工', '科幻']);
  });
});
