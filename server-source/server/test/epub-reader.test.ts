import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
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
import { extractEpubChapters } from '../src/library/epub-text.ts';
import { asEntries, buildZip } from './helpers/zipfix.ts';
import { bearer, seedUser, signIn } from './helpers/app.ts';

/**
 * The console reader's EPUB path.
 *
 * A TXT file can be split on heading lines in the browser; an EPUB cannot, because splitting it
 * means unzipping it. So the server walks the spine and rebuilds each document from a whitelist of
 * tags — and that whitelist is the security boundary, since the result is assigned into the admin
 * page's DOM. Most of this file is about that boundary: what survives the walk, what does not, and
 * what a document with no text at all does to the chapter list.
 */

const CONTAINER = `<?xml version="1.0" encoding="UTF-8"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`;

const OPF = `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>阅读器测试书</dc:title>
    <dc:identifier id="uid">urn:isbn:9780000000002</dc:identifier>
    <dc:language>zh-CN</dc:language>
  </metadata>
  <manifest>
    <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
    <item id="c3" href="text/c3.xhtml" media-type="application/xhtml+xml"/>
    <item id="c4" href="text/c4.xhtml" media-type="application/xhtml+xml"/>
    <item id="notes" href="text/notes.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
    <itemref idref="c3"/>
    <itemref idref="c4"/>
    <itemref idref="notes" linear="no"/>
  </spine>
</package>`;

function xhtml(body: string, title = ''): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${title}</title></head><body>${body}</body></html>`;
}

const CHAPTER_1 = xhtml(
  `<h1>第一章 起</h1>
   <p>Hello <em>world</em> !</p>
   <p>第二段<script>alert(1)</script><img src="x.png" alt=""/></p>
   <ul><li>甲</li><li>乙</li></ul>`,
  '阅读器测试书',
);

/** No heading, and a `<title>` that is not the book's — the fallback that makes the TOC readable. */
const CHAPTER_2 = xhtml('<p>没有标题的一节。</p>', '第二章 承');

/** Image only: no text, so it must not appear as an empty chapter in the TOC. */
const CHAPTER_3 = xhtml('<div><img src="only.png"/></div>', '插图页');

const CHAPTER_4 = xhtml('<h2>尾声</h2><blockquote><p>引用的一段。</p></blockquote><p>正文。</p>', '尾声');

/** `linear="no"` — auxiliary material a reader skips. */
const NOTES = xhtml('<p>注释。</p>', '注释');

function epubBytes(overrides: Record<string, string> = {}): Uint8Array {
  return buildZip(
    asEntries({
      mimetype: 'application/epub+zip',
      'META-INF/container.xml': CONTAINER,
      'OEBPS/content.opf': OPF,
      'OEBPS/text/c1.xhtml': CHAPTER_1,
      'OEBPS/text/c2.xhtml': CHAPTER_2,
      'OEBPS/text/c3.xhtml': CHAPTER_3,
      'OEBPS/text/c4.xhtml': CHAPTER_4,
      'OEBPS/text/notes.xhtml': NOTES,
      ...overrides,
    }),
  );
}

let dir: string;
const write = (name: string, bytes: Uint8Array): string => {
  const path = join(dir, name);
  writeFileSync(path, bytes);
  return path;
};

beforeAll(() => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-epub-reader-'));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

describe('extractEpubChapters', () => {
  it('walks the spine in order and titles each chapter from its heading', async () => {
    const content = await extractEpubChapters(write('book.epub', epubBytes()));

    expect(content.chapters.map((c) => c.title)).toEqual(['第一章 起', '第二章 承', '尾声']);
    expect(content.truncated).toBe(false);
    expect(content.totalChars).toBe(content.chapters.reduce((sum, c) => sum + c.chars, 0));
    expect(content.totalChars).toBeGreaterThan(20);
  });

  it('keeps inline emphasis and spacing around it', async () => {
    const content = await extractEpubChapters(write('emphasis.epub', epubBytes()));
    const html = content.chapters[0]?.html ?? '';

    expect(html).toContain('<p>Hello <em>world</em> !</p>');
  });

  it('drops scripts and images rather than passing markup through', async () => {
    const content = await extractEpubChapters(write('unsafe.epub', epubBytes()));
    const html = content.chapters[0]?.html ?? '';

    // The whole point: this string is assigned into the admin page's DOM.
    expect(html).not.toContain('script');
    expect(html).not.toContain('alert(1)');
    expect(html).not.toContain('<img');
    expect(html).toContain('第二段');
  });

  it('keeps lists as lists', async () => {
    const content = await extractEpubChapters(write('lists.epub', epubBytes()));

    expect(content.chapters[0]?.html).toContain('<ul><li>甲</li><li>乙</li></ul>');
  });

  it('does not repeat a heading that is already the chapter title', async () => {
    const content = await extractEpubChapters(write('heading.epub', epubBytes()));
    const first = content.chapters[0];

    expect(first?.html.startsWith('<p>')).toBe(true);
    expect(first?.html).not.toContain('<h1>');
  });

  it('skips a document with no text instead of listing an empty chapter', async () => {
    const content = await extractEpubChapters(write('skip.epub', epubBytes()));

    expect(content.chapters.map((c) => c.title)).not.toContain('插图页');
  });

  it('skips linear="no" documents', async () => {
    const content = await extractEpubChapters(write('linear.epub', epubBytes()));

    expect(content.chapters.map((c) => c.title)).not.toContain('注释');
  });

  it('keeps blockquotes as blockquotes', async () => {
    const content = await extractEpubChapters(write('quote.epub', epubBytes()));

    expect(content.chapters[2]?.html).toContain('<blockquote><p>引用的一段。</p></blockquote>');
  });

  it('refuses an archive with no OPF', async () => {
    const path = write(
      'not-an-epub.epub',
      buildZip(asEntries({ mimetype: 'application/epub+zip', 'notes.txt': '不是 EPUB' })),
    );

    await expect(extractEpubChapters(path)).rejects.toThrow(/OPF/u);
  });
});

// ---------------------------------------------------------------- the route

let app: FastifyInstance;
let token: string;
let bookId: string;

beforeAll(async () => {
  const library = join(dir, 'library');
  const db = openMigratedDb();
  app = await buildApp({
    config: loadConfig({
      NODE_ENV: 'test',
      EBOOK_LOG_LEVEL: 'silent',
      EBOOK_DATA_DIR: join(dir, 'db'),
      EBOOK_LIBRARY_DIR: library,
      EBOOK_JWT_SECRET: 'test-secret-test-secret-test-secret-1234567890',
      EBOOK_MASTER_KEY: 'test-master-key-test-master-key-1234567890',
    }),
    db,
  });
  await app.ready();

  const users = new UserRepo(db);
  await seedUser(users, { username: 'reader', password: 'reader-password-1' });
  token = (await signIn(app, 'reader', 'reader-password-1')).accessToken;

  // A real book row with a real EPUB blob, which is what the route resolves.
  const books = new BookRepo(db);
  bookId = books.upsertFromMetadata({
    meta: {
      format: 'epub',
      title: '阅读器测试书',
      contributors: [],
      tags: [],
      cover: null,
      source: 'epub',
      confidence: 0.9,
      warnings: [],
      drm: false,
    } as never,
    sha256: 'b'.repeat(64),
    filename: '阅读器测试书.epub',
    userId: null,
    mode: 'create',
  }).bookId;
  const blob = await new BlobRepo(db, library).putBuffer(Buffer.from(epubBytes()), 'application/epub+zip', 'epub');
  books.attachFile({
    bookId,
    blobId: blob.id,
    format: 'epub',
    mediaType: 'application/epub+zip',
    filename: '阅读器测试书.epub',
    sizeBytes: blob.size_bytes,
    sha256: blob.sha256,
    makePrimary: true,
  });
});

afterAll(async () => {
  await app.close();
});

describe('GET /api/v1/books/:id/epub', () => {
  it('returns chapters the console reader can render', async () => {
    const res = await app.inject({ method: 'GET', url: `/api/v1/books/${bookId}/epub`, headers: bearer(token) });

    expect(res.statusCode).toBe(200);
    const body = res.json() as {
      bookId: string;
      chapters: { title: string; html: string; chars: number }[];
      totalChars: number;
      truncated: boolean;
    };
    expect(body.bookId).toBe(bookId);
    expect(body.chapters).toHaveLength(3);
    expect(body.chapters[0]?.title).toBe('第一章 起');
    expect(body.chapters[0]?.html).toContain('Hello');
    expect(body.totalChars).toBeGreaterThan(20);
    expect(body.truncated).toBe(false);
  });

  it('requires a credential', async () => {
    const res = await app.inject({ method: 'GET', url: `/api/v1/books/${bookId}/epub` });
    expect(res.statusCode).toBe(401);
  });

  it('answers 404 for a book that has no EPUB file', async () => {
    const db = new BookRepo(openMigratedDb());
    const other = db.upsertFromMetadata({
      meta: {
        format: 'txt',
        title: '只有 TXT 的书',
        contributors: [],
        tags: [],
        cover: null,
        source: 'txt',
        confidence: 0.9,
        warnings: [],
        drm: false,
      } as never,
      sha256: 'c'.repeat(64),
      filename: '只有 TXT 的书.txt',
      userId: null,
      mode: 'create',
    }).bookId;

    const res = await app.inject({ method: 'GET', url: `/api/v1/books/${other}/epub`, headers: bearer(token) });
    expect(res.statusCode).toBe(404);
  });
});
