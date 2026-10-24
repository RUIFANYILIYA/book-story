import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { strToU8 } from 'fflate';
import { loadConfig, type Config } from '../src/config.ts';
import { openMigratedDb } from './helpers/db.ts';
import { asEntries, buildZip } from './helpers/zipfix.ts';
import { ingestFile, safeDownloadName } from '../src/library/ingest.ts';
import { BlobRepo } from '../src/db/repositories/blob.repo.ts';
import { BookRepo } from '../src/db/repositories/book.repo.ts';
import { UserRepo } from '../src/db/repositories/user.repo.ts';
import { hashPassword } from '../src/auth/password.ts';
import type { Db } from '../src/db/index.ts';
import { AppError } from '../src/errors.ts';

/**
 * The ingest pipeline end to end, against a real temporary library directory and a real SQLite
 * file. Nothing here mocks the filesystem: the failure modes that matter in an importer are
 * exactly the ones that only appear when a file has to be moved, hashed and named on disk.
 */

let dir: string;
let db: Db;
let config: Config;
let userId: string;

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-ingest-'));
  config = loadConfig({
    NODE_ENV: 'test',
    EBOOK_DATA_DIR: join(dir, 'db'),
    EBOOK_LIBRARY_DIR: join(dir, 'library'),
    EBOOK_LOG_LEVEL: 'silent',
  });
  db = openMigratedDb();
  const users = new UserRepo(db);
  userId = users.create({
    username: 'importer',
    displayName: 'Importer',
    passwordHash: await hashPassword('a-long-enough-password'),
  }).id;
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
});

function stage(name: string, data: Uint8Array): string {
  const path = join(dir, `stage-${Math.random().toString(36).slice(2)}-${name}`);
  writeFileSync(path, data);
  return path;
}

const CN_PARAGRAPH = '夜色从海面漫上来，灯塔的光一圈一圈扫过水面。\n\n第二天清晨，渡船靠岸。';

/**
 * Each call gets a unique body unless the test passes one deliberately. Without this, two
 * unrelated tests upload byte-identical files and the second is rejected by the content dedupe —
 * a correct pipeline behaviour that would otherwise look like a dedupe bug.
 */
let unique = 0;
async function ingestTxt(filename: string, text?: string, owner = userId) {
  const body = text ?? `${CN_PARAGRAPH}\n\n（记录 ${unique += 1}）`;
  return ingestFile({ db, config }, {
    sourcePath: stage(filename, strToU8(body)),
    filename,
    userId: owner,
    mode: 'auto',
  });
}

describe('ingestFile', () => {
  it('stores the bytes under a content-addressed path and links a book to them', async () => {
    const result = await ingestTxt('远山灯火 - 林某.txt');
    expect(result.created).toBe(true);
    expect(result.format).toBe('txt');
    expect(result.title).toBe('远山灯火');

    const stored = new BlobRepo(db, config.libraryDir).require(result.blobId);
    expect(stored.storage_path).toMatch(/^blobs\/[0-9a-f]{2}\/[0-9a-f]{2}\/[0-9a-f]{64}$/u);
    expect(readFileSync(join(config.libraryDir, stored.storage_path)).toString('utf8')).toContain('夜色');
    // The staging file is consumed, not left behind to be swept.
    expect(readdirSync(dir).some((n) => n.includes('远山灯火'))).toBe(false);
  });

  it('derives author, search text and a cover even though a TXT file carries none', async () => {
    const result = await ingestTxt('海雾之城 - 周甲.txt');
    const books = new BookRepo(db);
    const book = books.findById(result.bookId);
    expect(book).toBeDefined();

    const authors = db.all<{ name: string }>(
      `SELECT c.name FROM book_contributors bc JOIN contributors c ON c.id = bc.contributor_id
       WHERE bc.book_id = ?`,
      [result.bookId],
    );
    expect(authors.map((a) => a.name)).toContain('周甲');
    expect(book?.search_text).toContain('海雾');
    expect(book?.has_cover).toBe(1);

    const cover = new BlobRepo(db, config.libraryDir).require(book!.cover_blob_id!);
    expect(cover.media_type).toBe('image/svg+xml');
    expect(cover.size_bytes).toBeGreaterThan(100);
    // needs_review is set because a filename-derived title is a guess, not a fact.
    expect(book?.needs_review).toBe(1);
  });

  it('rejects the exact same bytes a second time without writing them again', async () => {
    const first = await ingestTxt('重复测试 - 某人.txt', '完全相同的一段中文正文内容，用于去重测试。');
    const blobCountBefore = db.get<{ n: number }>('SELECT count(*) AS n FROM blobs')?.n ?? 0;

    let error: unknown;
    try {
      await ingestTxt('另一个名字 - 某人.txt', '完全相同的一段中文正文内容，用于去重测试。');
    } catch (err) {
      error = err;
    }
    expect(error).toBeInstanceOf(AppError);
    expect((error as AppError).code).toBe('duplicate_content');
    expect((error as AppError).details?.bookId).toBe(first.bookId);
    expect(db.get<{ n: number }>('SELECT count(*) AS n FROM blobs')?.n).toBe(blobCountBefore);
  });

  it('classifies by content when the extension lies', async () => {
    // Real CBZ bytes named .txt. An extension-first importer would parse this as text and store a
    // binary blob as a novel.
    const zip = buildZip(
      asEntries({ 'p1.jpg': '', 'p2.jpg': '' }).map((e) => ({ ...e, data: Uint8Array.from([0xff, 0xd8, 0xff, 0xe0]) })),
    ) as unknown as Uint8Array;
    const result = await ingestFile({ db, config }, {
      sourcePath: stage('mislabelled.txt', zip),
      filename: 'mislabelled.txt',
      userId,
      mode: 'auto',
    });
    expect(result.format).toBe('cbz');
    const book = new BookRepo(db).findById(result.bookId);
    expect(book?.metadata_source).toBe('filename');
    expect(book?.needs_review).toBe(1);
  });

  it('honours a quota instead of letting one account fill the volume', async () => {
    const users = new UserRepo(db);
    // A dedicated account: usage accumulates per uploader, so sharing the suite-wide user would
    // make the numbers depend on how many tests ran before this one.
    const quotaUser = users.create({
      username: 'quota-user',
      displayName: '配额账号',
      passwordHash: await hashPassword('a-long-enough-password'),
      quotaBytes: null,
    }).id;
    users.setQuota(quotaUser, 1024 * 1024);

    const result = await ingestTxt('配额测试 - 甲.txt', '一段用于配额测试的中文内容文本。', quotaUser);
    const stored = new BlobRepo(db, config.libraryDir).require(result.blobId);
    expect(new BlobRepo(db, config.libraryDir).storageUsedBy(quotaUser)).toBe(stored.size_bytes);

    // A quota below the already-used total deletes nothing; it only refuses the next upload.
    users.setQuota(quotaUser, 1);
    let error: unknown;
    try {
      await ingestTxt('第二次上传 - 甲.txt', '另一段完全不同的中文内容，用来触发配额拒绝。', quotaUser);
    } catch (err) {
      error = err;
    }
    expect(error).toBeInstanceOf(AppError);
    expect((error as AppError).code).toBe('insufficient_storage');
    // Nothing was written for the refused upload, including its staging file.
    expect(new BlobRepo(db, config.libraryDir).storageUsedBy(quotaUser)).toBe(stored.size_bytes);
  });

  it('attaches a second format to the same book when title and author already match', async () => {
    const txt = await ingestTxt('同名书籍 - 丙.txt', '这是同名书籍的中文正文内容第一部分。');
    const cbz = buildZip(
      asEntries({ 'cover.jpg': '' }).map((e) => ({ ...e, data: Uint8Array.from([0xff, 0xd8, 0xff, 0xe0]) })),
    ) as unknown as Uint8Array;
    const second = await ingestFile({ db, config }, {
      sourcePath: stage('同名书籍 - 丙.cbz', cbz),
      filename: '同名书籍 - 丙.cbz',
      userId,
      mode: 'auto',
    });
    expect(second.created).toBe(false);
    expect(second.matchedBy).toBe('title-author');
    expect(second.bookId).toBe(txt.bookId);
    const files = db.all<{ format: string }>('SELECT format FROM book_files WHERE book_id = ?', [txt.bookId]);
    expect(files.map((f) => f.format).sort()).toEqual(['cbz', 'txt']);
  });

  it('persists a detected charset so a legacy file is never re-guessed on open', async () => {
    // c4 e3 ba c3 is 你好 in GBK and is not valid UTF-8.
    const pair = Uint8Array.from([0xc4, 0xe3, 0xba, 0xc3]);
    const gbk = new Uint8Array(pair.length * 80);
    for (let i = 0; i < 80; i += 1) gbk.set(pair, i * pair.length);
    const result = await ingestFile({ db, config }, {
      sourcePath: stage('旧站存档.txt', gbk),
      filename: '旧站存档.txt',
      userId,
      mode: 'auto',
    });
    const charset = db.get<{ charset: string | null }>('SELECT charset FROM book_files WHERE id = ?', [result.fileId])
      ?.charset;
    expect(charset).toBeTruthy();
    expect(charset).not.toBe('UTF-8');
  });
});

describe('safeDownloadName', () => {
  it('removes what would escape a directory or break a Content-Disposition header', () => {
    expect(safeDownloadName('../../etc/passwd.epub', 'epub')).toBe('passwd.epub');
    expect(safeDownloadName('a:b*c?.txt', 'txt')).toBe('abc.txt');
    expect(safeDownloadName('  .txt', 'txt')).toBe('book.txt');
    expect(safeDownloadName('冬谷夜话.pdf', 'pdf')).toBe('冬谷夜话.pdf');
    // The extension always comes from the sniffed format, never from the uploaded name.
    expect(safeDownloadName('book.evil.exe', 'epub')).toBe('book.evil.epub');
  });

  it('bounds the length so a 4 KB filename cannot wedge a header', () => {
    const long = safeDownloadName(`${'x'.repeat(500)}.txt`, 'txt');
    expect(long.length).toBeLessThanOrEqual(185);
  });
});
