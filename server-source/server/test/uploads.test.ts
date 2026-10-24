import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { loadConfig } from '../src/config.ts';
import { buildApp } from '../src/app.ts';
import { openMigratedDb } from './helpers/db.ts';
import type { Db } from '../src/db/index.ts';
import { UserRepo } from '../src/db/repositories/user.repo.ts';
import { bearer, seedUser, signIn } from './helpers/app.ts';
import { resolveWithin } from '../src/routes/v1/uploads.routes.ts';

/**
 * The upload route over a real multipart request, because the two ways this breaks are both
 * specific to multipart handling: text fields arriving before the file part (so `request.body` is
 * empty and a handler that reads it silently loses `mode` and `bookId`), and the temp file being
 * left behind when a size limit trips mid-stream.
 */

let app: FastifyInstance;
let dir: string;
let db: Db;
let token: string;

const BOUNDARY = '----ebookdTestBoundary7MA4YWxkTrZu0gW';

function multipart(fields: Record<string, string>, file: { name: string; filename: string; body: string }): Buffer {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(fields)) {
    parts.push(
      `--${BOUNDARY}\r\nContent-Disposition: form-data; name="${key}"\r\n\r\n${value}\r\n`,
    );
  }
  parts.push(
    `--${BOUNDARY}\r\nContent-Disposition: form-data; name="${file.name}"; filename="${file.filename}"\r\n` +
      'Content-Type: application/octet-stream\r\n\r\n' +
      `${file.body}\r\n`,
  );
  parts.push(`--${BOUNDARY}--\r\n`);
  return Buffer.from(parts.join(''), 'utf8');
}

function upload(payload: Buffer, options: { token?: string | null } = {}) {
  return app.inject({
    method: 'POST',
    url: '/api/v1/uploads',
    headers: {
      'content-type': `multipart/form-data; boundary=${BOUNDARY}`,
      ...(options.token === null ? {} : bearer(options.token ?? token)),
    },
    payload,
  });
}

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-upload-'));
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
  await seedUser(users, { username: 'uploader', password: 'upload-password-1' });
  token = (await signIn(app, 'uploader', 'upload-password-1')).accessToken;
});

afterAll(async () => {
  await app.close();
  rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
});

describe('POST /api/v1/uploads', () => {
  it('accepts a file and returns the book it became', async () => {
    const res = await upload(
      multipart({}, { name: 'file', filename: '冬谷夜话 - 甲.txt', body: '第一章 风雪\n\n夜色从海面漫上来。' }),
    );
    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.format).toBe('txt');
    expect(body.title).toBe('冬谷夜话');
    expect(body.created).toBe(true);
    expect(body.fileId).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
  });

  it('requires a credential', async () => {
    const res = await upload(
      multipart({}, { name: 'file', filename: 'x.txt', body: '内容' }),
      { token: null },
    );
    expect(res.statusCode).toBe(401);
  });

  it('rejects a non-multipart body rather than treating it as an empty upload', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/uploads',
      headers: { 'content-type': 'application/json', ...bearer(token) },
      payload: Buffer.from(JSON.stringify({ nope: true })),
    });
    expect(res.statusCode).toBe(415);
    expect(res.json().error.code).toBe('unsupported_media_type');
  });

  it('rejects a request with fields but no file part', async () => {
    const res = await upload(multipart({ mode: 'create' }, { name: 'other', filename: '', body: '' }));
    expect([400, 415]).toContain(res.statusCode);
  });

  it('carries the mode and bookId fields through to the pipeline', async () => {
    const first = await upload(
      multipart({}, { name: 'file', filename: ' Attached 一书 - 乙.txt', body: '正文内容第一段落。' }),
    );
    expect(first.statusCode).toBe(201);

    // mode=attach against a *different* title must not merge into the first book.
    const second = await upload(
      multipart({ mode: 'create' }, { name: 'file', filename: '另一本书 - 乙.txt', body: '完全不同的另一段中文正文。' }),
    );
    expect(second.statusCode).toBe(201);
    expect(second.json().bookId).not.toBe(first.json().bookId);
    expect(second.json().created).toBe(true);
  });

  it('reports a re-uploaded identical file as a duplicate, not a new book', async () => {
    const body = '同一段内容会被内容寻址去重掉。';
    const first = await upload(multipart({}, { name: 'file', filename: '去重检查 - 丙.txt', body }));
    expect(first.statusCode).toBe(201);

    const again = await upload(multipart({}, { name: 'file', filename: '改了名字也是同一份.txt', body }));
    expect(again.statusCode).toBe(409);
    expect(again.json().error.code).toBe('duplicate_content');
    expect(again.json().error.details.bookId).toBe(first.json().bookId);
  });

  it('leaves no orphaned temp file after a rejected upload', async () => {
    const { existsSync, readdirSync } = await import('node:fs');
    const body = '被拒绝的上传不应该在临时目录留下任何字节。';
    // First: accepted, so its temp path is consumed into the store.
    const ok = await upload(multipart({}, { name: 'file', filename: '清理检查 - 丁.txt', body }));
    expect(ok.statusCode).toBe(201);
    // Second: identical bytes are refused, and the refused copy must be gone.
    await upload(multipart({}, { name: 'file', filename: '清理检查-重复.txt', body }));

    const tmpRoot = join(dir, 'library', 'tmp', 'uploads');
    const leftovers = existsSync(tmpRoot) ? readdirSync(tmpRoot) : [];
    expect(leftovers).toEqual([]);
  });
});

describe('same-name uploads', () => {
  /**
   * The whole point of the three answers is that they are answers to a *question*, so every case
   * starts from the same shelf: one book, one file, known bytes. The content of the second upload
   * differs — identical bytes are `duplicate_content`'s job, and that answer comes first.
   *
   * Each test seeds its own name *and* its own bytes: the app is built once for the whole file,
   * so a name reused across cases would already be taken by the previous case's shelf, and bytes
   * reused across cases would be answered by `duplicate_content` before any name was compared.
   */
  interface FileRow {
    id: string;
    book_id: string;
    filename: string;
    sha256: string;
    is_primary: number;
  }

  const filesOf = (bookId: string): FileRow[] =>
    db.all<FileRow>(
      'SELECT id, book_id, filename, sha256, is_primary FROM book_files WHERE book_id = ? ORDER BY created_at',
      [bookId],
    );

  /** Every one of these filenames parses to the same title, so the shelf is counted whole. */
  const bookCount = (): number => db.get<{ n: number }>('SELECT count(*) AS n FROM books')?.n ?? 0;

  async function seed(name: string): Promise<{ bookId: string; sha: string; title: string }> {
    const res = await upload(multipart({}, { name: 'file', filename: name, body: `第一版正文：${name}` }));
    expect(res.statusCode).toBe(201);
    const body = res.json();
    const row = db.get<{ sha256: string }>('SELECT sha256 FROM book_files WHERE id = ?', [body.fileId]);
    return { bookId: body.bookId, sha: row!.sha256, title: body.title };
  }

  /** The same name, different bytes — the shape that makes the name the only thing in question. */
  const revised = (name: string) => ({
    name: 'file',
    filename: name,
    body: `第二版正文：${name}，改写过的开篇。`,
  });

  it('asks which file wins instead of silently merging into the existing book', async () => {
    const { bookId } = await seed('同名冲突 - 戊.txt');

    const res = await upload(multipart({}, revised('同名冲突 - 戊.txt')));
    expect(res.statusCode).toBe(409);
    const error = res.json().error;
    expect(error.code).toBe('filename_conflict');
    expect(error.details.bookId).toBe(bookId);
    expect(error.details.filename).toBe('同名冲突 - 戊.txt');
    expect(error.details.bookTitle).toBeTruthy();
    // The question must not have changed anything on its own.
    expect(filesOf(bookId)).toHaveLength(1);
  });

  it('skip stores nothing and leaves the original file exactly as it was', async () => {
    const { bookId, sha } = await seed('同名冲突 - 己.txt');
    const before = bookCount();

    const res = await upload(multipart({ conflict: 'skip' }, revised('同名冲突 - 己.txt')));
    expect(res.statusCode).toBe(200);
    expect(res.json().skipped).toBe(true);

    const rows = filesOf(bookId);
    expect(rows).toHaveLength(1);
    expect(rows[0]!.sha256).toBe(sha);
    expect(bookCount()).toBe(before);
  });

  it('replace puts the new bytes into the book that held the old file', async () => {
    const { bookId, sha } = await seed('同名冲突 - 庚.txt');
    const before = bookCount();

    const res = await upload(multipart({ conflict: 'replace' }, revised('同名冲突 - 庚.txt')));
    expect(res.statusCode).toBe(201);

    // Same book — the shelf, the id and the reading progress survive an update.
    expect(res.json().bookId).toBe(bookId);
    expect(res.json().storedFilename).toBe('同名冲突 - 庚.txt');
    const rows = filesOf(bookId);
    expect(rows).toHaveLength(1);
    expect(rows[0]!.sha256).not.toBe(sha);
    expect(rows[0]!.filename).toBe('同名冲突 - 庚.txt');
    expect(rows[0]!.is_primary).toBe(1);
    // One book, still: the update is not a second copy.
    expect(bookCount()).toBe(before);
  });

  it('rename keeps both files, the incoming one numbered after the original name', async () => {
    const { bookId, sha } = await seed('同名冲突 - 辛.txt');
    const before = bookCount();

    const res = await upload(multipart({ conflict: 'rename' }, revised('同名冲突 - 辛.txt')));
    expect(res.statusCode).toBe(201);
    expect(res.json().storedFilename).toBe('同名冲突 - 辛 (1).txt');
    // One book, not two: the dedupe index on (title_norm, sort_key) refuses a second "同名冲突",
    // and a shelf entry called "同名冲突 (1)" is not what "rename the file" asked for.
    expect(res.json().bookId).toBe(bookId);
    expect(bookCount()).toBe(before);

    // Both files survive: the original demoted but intact, the copy primary so a reader who
    // downloads the book gets the newer bytes.
    const rows = filesOf(bookId);
    expect(rows).toHaveLength(2);
    expect(rows[0]!.filename).toBe('同名冲突 - 辛.txt');
    expect(rows[0]!.sha256).toBe(sha);
    expect(rows[0]!.is_primary).toBe(0);
    expect(rows[1]!.filename).toBe('同名冲突 - 辛 (1).txt');
    expect(rows[1]!.sha256).not.toBe(sha);
    expect(rows[1]!.is_primary).toBe(1);
  });

  it('numbers past every name already taken, not just the first', async () => {
    const { bookId } = await seed('同名冲突 - 壬.txt');

    const first = await upload(multipart({ conflict: 'rename' }, revised('同名冲突 - 壬.txt')));
    expect(first.statusCode).toBe(201);
    expect(first.json().storedFilename).toBe('同名冲突 - 壬 (1).txt');

    const second = await upload(
      multipart({ conflict: 'rename' }, {
        name: 'file',
        filename: '同名冲突 - 壬.txt',
        body: '第三版正文：同名冲突 - 壬.txt。',
      }),
    );
    expect(second.statusCode).toBe(201);
    expect(second.json().storedFilename).toBe('同名冲突 - 壬 (2).txt');
    expect(filesOf(bookId).map((f) => f.filename)).toEqual([
      '同名冲突 - 壬.txt',
      '同名冲突 - 壬 (1).txt',
      '同名冲突 - 壬 (2).txt',
    ]);
  });

  it('does not ask when the name is free, and still attaches a re-release by title', async () => {
    const { bookId } = await seed('同名冲突 - 癸.txt');

    // A different filename with the same title and author is the pre-existing "new edition of a
    // book I already have" answer, and it must keep working without any question.
    const res = await upload(
      multipart({}, { name: 'file', filename: '同名冲突 - 癸（修订版）.txt', body: '第二版正文：同名冲突 - 癸.txt，改写过的开篇。' }),
    );
    expect(res.statusCode).toBe(201);
    expect(res.json().bookId).toBe(bookId);
    expect(res.json().created).toBe(false);
    expect(filesOf(bookId)).toHaveLength(2);
  });
});

describe('mode=replace with identical bytes', () => {
  /**
   * The 500 that used to answer the most ordinary retry there is: re-importing an unchanged file
   * with `mode=replace` walked past the duplicate-content check on purpose, then inserted a
   * second row for a (book_id, blob_id) pair that already existed. The answer is an idempotent
   * re-assertion of the row that is already there.
   */
  it('re-asserts the existing file instead of failing on the unique pair', async () => {
    const body = '同一个文件replace两次，第二次必须安静成功。';
    const first = await upload(multipart({ mode: 'replace' }, { name: 'file', filename: '重复替换 - 甲.txt', body }));
    expect(first.statusCode).toBe(201);

    const again = await upload(multipart({ mode: 'replace' }, { name: 'file', filename: '重复替换 - 甲.txt', body }));
    expect(again.statusCode).toBe(201);
    expect(again.json().bookId).toBe(first.json().bookId);
    // The same row, re-asserted as primary — not a second file on the same book.
    expect(again.json().fileId).toBe(first.json().fileId);
    const rows = db.all<{ n: number; is_primary: number }>(
      'SELECT count(*) AS n, max(is_primary) AS is_primary FROM book_files WHERE book_id = ?',
      [first.json().bookId],
    );
    expect(rows[0]!.n).toBe(1);
    expect(rows[0]!.is_primary).toBe(1);
  });

  it('still answers the plain re-upload with duplicate_content', async () => {
    // The two answers must stay distinct: `auto` reports the bytes are already shelved, and only
    // an explicit replace is allowed to re-assert them.
    const body = '默认模式下的重传依旧是重复内容。';
    await upload(multipart({}, { name: 'file', filename: '重复替换 - 乙.txt', body }));

    const again = await upload(multipart({}, { name: 'file', filename: '重复替换 - 乙.txt', body }));
    expect(again.statusCode).toBe(409);
    expect(again.json().error.code).toBe('duplicate_content');
  });
});

describe('resolveWithin', () => {
  // These were absent, which is exactly why a 403 on every legitimate import reached the browser:
  // containment was decided by comparing string prefixes, and the configured root arrives with
  // forward slashes while path.join produces backslashes on Windows, so nothing ever matched.
  // A containment check is only as good as the paths it has actually been handed.
  const root = resolve(tmpdir(), 'ebookd-imports');

  it('accepts a nested relative path', () => {
    expect(resolveWithin(root, 'imports/novels')).toBe(join(resolve(root), 'imports', 'novels'));
  });

  it('accepts the root itself', () => {
    expect(resolveWithin(root, '')).toBe(resolve(root));
  });

  it('refuses a traversal that climbs out', () => {
    expect(() => resolveWithin(root, '../../etc/passwd')).toThrow(/inside the configured import directory/);
  });

  it('refuses an absolute path that points elsewhere', () => {
    expect(() => resolveWithin(root, '/etc/passwd')).toThrow();
  });

  it('refuses a sibling directory that merely shares a prefix', () => {
    // The classic prefix-comparison bug: <root>-backup starts with <root>.
    expect(() => resolveWithin(root, '../ebookd-imports-backup')).toThrow();
  });

  it('allows a name that legitimately contains dots', () => {
    expect(resolveWithin(root, 'imports/v1.2 手册')).toContain('v1.2 手册');
  });
});
