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
import { placeholderCoverSvg, SVG_MEDIA_TYPE } from '../src/library/covers.ts';
import { newId } from '../src/ids.ts';
import { bearer, seedUser, signIn } from './helpers/app.ts';

/**
 * Editing the title or the author must redraw a generated cover.
 *
 * The placeholder tile is drawn once, at ingest, from whatever the metadata said then — so a
 * hand edit through the console used to leave the shelf advertising the old title forever. The
 * server now re-renders it on the way out of `PATCH /books/:id`, and the cover stamp it returns
 * changes with it, which is what lets the console (and any other client) bust its cache.
 *
 * What must *not* be redrawn is the other half of the promise: an embedded cover is a picture
 * with no text in it, and a reader's own upload is theirs. Both are asserted here.
 */

const ISO = '2026-01-01T00:00:00.000Z';
/** Magic bytes only — the route under test never decodes the image, so the fixture need not be one. */
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d]);

let app: FastifyInstance;
let dir: string;
let libraryDir: string;
let db: ReturnType<typeof openMigratedDb>;
let editor: string;

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-redraw-'));
  libraryDir = join(dir, 'library');
  db = openMigratedDb();
  app = await buildApp({
    config: loadConfig({
      NODE_ENV: 'test',
      EBOOK_LOG_LEVEL: 'silent',
      EBOOK_DATA_DIR: join(dir, 'db'),
      EBOOK_LIBRARY_DIR: libraryDir,
      EBOOK_JWT_SECRET: 'test-secret-test-secret-test-secret-1234567890',
      EBOOK_MASTER_KEY: 'test-master-key-test-master-key-1234567890',
    }),
    db,
  });
  await app.ready();

  const users = new UserRepo(db);
  await seedUser(users, { username: 'editor', password: 'editor-password-1', role: 'admin' });
  editor = (await signIn(app, 'editor', 'editor-password-1')).accessToken;
});

afterAll(async () => {
  await app.close();
  rmSync(dir, { recursive: true, force: true });
});

/** A book row, the way `insertBook` in the other tests makes one. */
function insertBookRow(title: string): string {
  const id = newId();
  db.run(
    `INSERT INTO books (id, title, title_norm, sort_key, search_text, metadata_source,
                        content_updated_at, added_at, updated_at, deleted_at)
     VALUES (?, ?, ?, ?, ?, 'filename', ?, ?, ?, NULL)`,
    [id, title, title.toLowerCase(), `排序 — ${id}`, `${title} 作者`, ISO, ISO, ISO],
  );
  return id;
}

/** A book whose cover is the generated placeholder — what a coverless ingest leaves behind. */
async function bookWithPlaceholder(title: string, author: string | null): Promise<string> {
  const id = insertBookRow(title);
  const books = new BookRepo(db);
  if (author) books.setContributors(id, [{ name: author, role: 'author' }]);
  const svg = placeholderCoverSvg({ title, author, seed: id });
  const blob = await new BlobRepo(db, libraryDir).putBuffer(svg, SVG_MEDIA_TYPE, 'cover-placeholder');
  books.setCover(id, blob.id);
  return id;
}

/** A book whose cover is a real image, the way an EPUB with cover art leaves behind. */
async function bookWithEmbeddedCover(title: string): Promise<string> {
  const id = insertBookRow(title);
  const books = new BookRepo(db);
  const blob = await new BlobRepo(db, libraryDir).putBuffer(PNG, 'image/png', 'cover');
  books.setCover(id, blob.id);
  return id;
}

async function fetchCover(id: string): Promise<{ statusCode: number; bytes: Buffer; etag: string }> {
  const res = await app.inject({ method: 'GET', url: `/api/v1/covers/${id}`, headers: bearer(editor) });
  return {
    statusCode: res.statusCode,
    bytes: Buffer.from(res.rawPayload),
    etag: String(res.headers.etag ?? ''),
  };
}

async function patchBook(id: string, body: Record<string, unknown>) {
  return await app.inject({
    method: 'PATCH',
    url: `/api/v1/books/${id}`,
    headers: bearer(editor),
    payload: body,
  });
}

function coverStampOf(id: string): string | null {
  const row = db.get<{ cover_updated_at: string | null }>(
    'SELECT cover_updated_at FROM books WHERE id = ?',
    [id],
  );
  return row?.cover_updated_at ?? null;
}

describe('PATCH /books/:id redraws a generated cover', () => {
  it('re-renders the tile with the new title', async () => {
    const id = await bookWithPlaceholder('旧标题', '旧作者');

    const before = await fetchCover(id);
    expect(before.statusCode).toBe(200);
    expect(before.bytes.toString('utf8')).toContain('旧标题');

    const res = await patchBook(id, { title: '新标题' });
    expect(res.statusCode).toBe(200);

    const after = await fetchCover(id);
    expect(after.bytes.toString('utf8')).toContain('新标题');
    expect(after.bytes.toString('utf8')).not.toContain('旧标题');
    // A new tile is a new blob, so the ETag moves too — otherwise a client that caches by it
    // (every browser does) would keep the old one.
    expect(after.etag).not.toBe(before.etag);
  });

  it('re-renders the tile with the new author', async () => {
    const id = await bookWithPlaceholder('不变的书名', '旧作者');

    await patchBook(id, { authors: ['新作者'] });

    const svg = (await fetchCover(id)).bytes.toString('utf8');
    expect(svg).toContain('新作者');
    expect(svg).not.toContain('旧作者');
  });

  it('re-renders when only the author changed but keeps the title', async () => {
    const id = await bookWithPlaceholder('书名不动', '甲');

    await patchBook(id, { authors: ['乙'] });

    const svg = (await fetchCover(id)).bytes.toString('utf8');
    expect(svg).toContain('书名不动');
    expect(svg).toContain('乙');
  });

  it('moves the cover stamp so a client can bust its cache', async () => {
    const id = await bookWithPlaceholder('旧标题', '旧作者');
    const before = coverStampOf(id);

    await patchBook(id, { title: '另一个标题' });

    expect(coverStampOf(id)).not.toBe(before);
    // The stamp travels in the summary, which is where the console reads it from.
    const list = await app.inject({ method: 'GET', url: '/api/v1/books?limit=200', headers: bearer(editor) });
    const row = (list.json().items as { id: string; coverUpdatedAt: string | null }[]).find(
      (b) => b.id === id,
    );
    expect(row?.coverUpdatedAt).toBe(coverStampOf(id));
  });

  it('leaves an embedded cover untouched — a picture has no text to redraw', async () => {
    const id = await bookWithEmbeddedCover('有封面的书');

    const before = await fetchCover(id);
    expect(before.bytes.equals(PNG)).toBe(true);

    await patchBook(id, { title: '改了书名' });

    const after = await fetchCover(id);
    expect(after.bytes.equals(PNG)).toBe(true);
    expect(after.etag).toBe(before.etag);
  });

  it("leaves a reader's own cover untouched — it is not the server's to redraw", async () => {
    const id = await bookWithPlaceholder('旧标题', '旧作者');
    const upload = await new BlobRepo(db, libraryDir).putBuffer(PNG, 'image/png', 'cover');
    db.run(
      `INSERT INTO user_book_states (user_id, book_id, shelf, status, created_at, updated_at, rev,
                                     cover_override_blob_id, cover_override_updated_at)
       VALUES ((SELECT id FROM users WHERE username = 'editor'), ?, 'library', 'none', ?, ?, 1, ?, ?)`,
      [id, ISO, ISO, upload.id, ISO],
    );

    // The override wins on the read path, so the bytes prove it survived the edit.
    const before = await fetchCover(id);
    expect(before.bytes.equals(PNG)).toBe(true);

    await patchBook(id, { title: '新标题' });

    const after = await fetchCover(id);
    expect(after.bytes.equals(PNG)).toBe(true);
    expect(after.etag).toBe(before.etag);
  });

  it('does not redraw on an edit that cannot appear on a tile', async () => {
    const id = await bookWithPlaceholder('旧标题', '旧作者');

    const before = await fetchCover(id);
    await patchBook(id, { description: '只是改了简介' });
    const after = await fetchCover(id);

    expect(after.bytes.equals(before.bytes)).toBe(true);
    expect(after.etag).toBe(before.etag);
  });

  it('survives a title that is changed and changed back', async () => {
    const id = await bookWithPlaceholder('最初的标题', '旧作者');
    const original = await fetchCover(id);

    await patchBook(id, { title: '临时标题' });
    await patchBook(id, { title: '最初的标题' });

    const back = await fetchCover(id);
    expect(back.bytes.equals(original.bytes)).toBe(true);
    expect(back.bytes.toString('utf8')).toContain('最初的标题');
  });
});
