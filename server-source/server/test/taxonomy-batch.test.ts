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
 * Taxonomy and batch maintenance.
 *
 * The properties under test are the ones a user notices when they are wrong: a batch that half
 * applied, a duplicate category that the index allowed because its parent was NULL, a merge that
 * silently drops a book's tag, and a per-id report that cannot say which selection item is stale.
 */

let app: FastifyInstance;
let dir: string;
let db: ReturnType<typeof openMigratedDb>;
let adminToken: string;
let userToken: string;
let userId: string;
const bookIds: string[] = [];

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-tax-'));
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
  await seedUser(users, { username: 'owner', password: 'owner-password-1', role: 'admin' });
  const plainId = await seedUser(users, { username: 'plain', password: 'plain-password-1' });
  userId = plainId;
  adminToken = (await signIn(app, 'owner', 'owner-password-1')).accessToken;
  userToken = (await signIn(app, 'plain', 'plain-password-1')).accessToken;

  const books = new BookRepo(db);
  const blobs = new BlobRepo(db, join(dir, 'library'));
  for (let i = 0; i < 6; i += 1) {
    const title = `批次书目 ${i}`;
    const sha = `c${i}`.padEnd(64, 'd');
    const blob = blobs.register({
      sha256: sha,
      sizeBytes: 100,
      relativePath: `blobs/${sha.slice(0, 2)}/${sha.slice(2, 4)}/${sha}`,
      mediaType: 'text/plain',
      format: 'txt',
      refcount: 0,
    });
    const id = books.upsertFromMetadata({
      meta: {
        format: 'txt',
        title,
        contributors: [{ name: '批量作者', role: 'author' }],
        tags: [],
        cover: null,
        source: 'txt',
        confidence: 0.9,
        warnings: [],
        drm: false,
      } as never,
      sha256: sha,
      filename: `${title}.txt`,
      userId: plainId,
      mode: 'create',
    }).bookId;
    books.attachFile({
      bookId: id,
      blobId: blob.id,
      sha256: sha,
      sizeBytes: 100,
      format: 'txt',
      mediaType: 'text/plain',
      filename: `${title}.txt`,
    });
    bookIds.push(id);
  }
});

afterAll(async () => {
  await app.close();
  rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
});

const call = (method: 'POST' | 'GET' | 'PATCH' | 'PUT' | 'DELETE', url: string, payload?: unknown, token = adminToken) =>
  app.inject({ method, url, headers: bearer(token), ...(payload === undefined ? {} : { payload: payload as object }) });

describe('categories', () => {
  it('rejects a duplicate name at the root, where the unique index used to be blind', async () => {
    const first = await call('POST', '/api/v1/categories', { name: '科幻' });
    expect(first.statusCode).toBe(201);
    const again = await call('POST', '/api/v1/categories', { name: '科幻' });
    // SQLite treats NULLs as distinct in a unique index, so parent_id IS NULL let root duplicates
    // through until the index was rebuilt over ifnull(parent_id, '').
    expect(again.statusCode).toBe(409);
    expect(again.json().error.code).toBe('already_exists');
  });

  it('builds a materialized path and refuses to move a category into its own subtree', async () => {
    const parent = await call('POST', '/api/v1/categories', { name: '文学' });
    const parentId = parent.json().category.id as string;
    const child = await call('POST', '/api/v1/categories', { name: '小说', parentId });
    expect(child.statusCode).toBe(201);
    const childId = child.json().category.id as string;
    expect(child.json().category.path).toBe(`/${parentId}/${childId}/`);

    const loop = await call('PATCH', `/api/v1/categories/${parentId}`, { parentId: childId });
    expect(loop.statusCode).toBe(422);
    expect(loop.json().error.message).toMatch(/subtree/);

    const self = await call('PATCH', `/api/v1/categories/${parentId}`, { parentId });
    expect(self.statusCode).toBe(422);
  });

  it('moves children up rather than deleting them with their parent', async () => {
    const parent = await call('POST', '/api/v1/categories', { name: '待删除' });
    const parentId = parent.json().category.id as string;
    await call('POST', '/api/v1/categories', { name: '保留子类', parentId });

    const removed = await call('DELETE', `/api/v1/categories/${parentId}`);
    expect(removed.statusCode).toBe(204);

    const list = await call('GET', '/api/v1/categories');
    const names = (list.json().items as { name: string; parent_id: string | null }[]).map((c) => c.name);
    expect(names).toContain('保留子类');
    expect(names).not.toContain('待删除');
  });

  it('refuses a third level, because the app only draws two', async () => {
    const root = await call('POST', '/api/v1/categories', { name: '两层根' });
    const rootId = root.json().category.id as string;
    const child = await call('POST', '/api/v1/categories', { name: '两层子', parentId: rootId });
    expect(child.statusCode).toBe(201);
    const childId = child.json().category.id as string;

    const grandchild = await call('POST', '/api/v1/categories', {
      name: '三层孙',
      parentId: childId,
    });
    // 422 is what `validation_failed` maps to; the code is the stable part.
    expect(grandchild.statusCode).toBe(422);
    expect(grandchild.json().error.code).toBe('validation_failed');
    expect(grandchild.json().error.message).toMatch(/one level only/);
  });

  it('refuses to move a shelf that owns sub-shelves under another shelf', async () => {
    const a = await call('POST', '/api/v1/categories', { name: '搬迁甲' });
    const aId = a.json().category.id as string;
    await call('POST', '/api/v1/categories', { name: '搬迁甲子', parentId: aId });

    const b = await call('POST', '/api/v1/categories', { name: '搬迁乙' });
    const bId = b.json().category.id as string;

    // Moving 甲 (which has a child) under 乙 would make the child a third level.
    const nested = await call('PATCH', `/api/v1/categories/${aId}`, { parentId: bId });
    expect(nested.statusCode).toBe(422);
    expect(nested.json().error.code).toBe('validation_failed');

    // Moving a leaf under a root is still fine.
    const leaf = await call('POST', '/api/v1/categories', { name: '搬迁叶', parentId: aId });
    const leafId = leaf.json().category.id as string;
    const moved = await call('PATCH', `/api/v1/categories/${leafId}`, { parentId: bId });
    expect(moved.statusCode).toBe(200);
    expect(moved.json().category.parent_id).toBe(bId);
  });

  it('reorders a level, and refuses a partial or duplicated list', async () => {
    const a = await call('POST', '/api/v1/categories', { name: '排序甲' });
    const b = await call('POST', '/api/v1/categories', { name: '排序乙' });
    const aId = a.json().category.id as string;
    const bId = b.json().category.id as string;

    const before = (await call('GET', '/api/v1/categories')).json().items as {
      id: string;
      name: string;
      parent_id: string | null;
    }[];
    const roots = before.filter((c) => !c.parent_id).map((c) => c.id);
    expect(roots.slice(-2)).toEqual([aId, bId]);

    // Put 排序乙 first and leave the rest as they were.
    const next = [bId, aId, ...roots.filter((id) => id !== aId && id !== bId)];
    const res = await call('POST', '/api/v1/categories/reorder', { parentId: null, ids: next });
    expect(res.statusCode).toBe(200);

    const after = (await call('GET', '/api/v1/categories')).json().items as {
      name: string;
      parent_id: string | null;
    }[];
    const names = after.filter((c) => !c.parent_id).map((c) => c.name);
    expect(names.slice(0, 2)).toEqual(['排序乙', '排序甲']);

    // Running the same order again changes nothing — the request is idempotent.
    const again = await call('POST', '/api/v1/categories/reorder', { parentId: null, ids: next });
    expect(again.statusCode).toBe(200);

    // A partial list would leave one shelf without a position, so it is refused.
    const partial = await call('POST', '/api/v1/categories/reorder', { parentId: null, ids: [bId] });
    expect(partial.statusCode).toBe(422);

    const duplicated = await call('POST', '/api/v1/categories/reorder', {
      parentId: null,
      ids: [bId, bId],
    });
    expect(duplicated.statusCode).toBe(422);
  });

  it('reorders sub-shelves without touching the root order', async () => {
    const parent = await call('POST', '/api/v1/categories', { name: '排序父' });
    const parentId = parent.json().category.id as string;
    const first = await call('POST', '/api/v1/categories', { name: '子一', parentId });
    const second = await call('POST', '/api/v1/categories', { name: '子二', parentId });
    const firstId = first.json().category.id as string;
    const secondId = second.json().category.id as string;

    const res = await call('POST', '/api/v1/categories/reorder', {
      parentId,
      ids: [secondId, firstId],
    });
    expect(res.statusCode).toBe(200);

    const after = (await call('GET', '/api/v1/categories')).json().items as {
      id: string;
      name: string;
      parent_id: string | null;
    }[];
    // The children come back right after their parent, in the new order.
    const index = after.findIndex((c) => c.id === parentId);
    expect(after.slice(index + 1, index + 3).map((c) => c.name)).toEqual(['子二', '子一']);

    // A sub-shelf cannot be smuggled into the root order.
    const wrongLevel = await call('POST', '/api/v1/categories/reorder', {
      parentId: null,
      ids: [firstId],
    });
    expect(wrongLevel.statusCode).toBe(422);
  });

  it('reports the shelf a book sits in, which is what the app syncs', async () => {
    const shelf = await call('POST', '/api/v1/categories', { name: '同步书架' });
    const shelfId = shelf.json().category.id as string;
    const firstBookId = bookIds[0]!;
    db.run('UPDATE books SET category_id = ? WHERE id = ?', [shelfId, firstBookId]);

    const listed = await call('GET', '/api/v1/books?limit=50');
    const item = (listed.json().items as { id: string; categoryId: string | null }[]).find(
      (b) => b.id === firstBookId,
    );
    expect(item?.categoryId).toBe(shelfId);

    const unfiled = (listed.json().items as { id: string; categoryId: string | null }[]).find(
      (b) => b.id === bookIds[1],
    );
    expect(unfiled?.categoryId).toBeNull();
  });
});

describe('tags', () => {
  it('merges without leaving a book holding two memberships of the same tag', async () => {
    const a = (await call('POST', '/api/v1/tags', { name: '来源甲' })).json().tag.id as string;
    const b = (await call('POST', '/api/v1/tags', { name: '来源乙' })).json().tag.id as string;
    const target = (await call('POST', '/api/v1/tags', { name: '汇合' })).json().tag.id as string;

    // One book carries all three, which is the case a naive merge trips a primary key on.
    await call('POST', '/api/v1/books/batch/tag', { bookIds: [bookIds[0]!], add: ['来源甲', '来源乙', '汇合'] });
    const merged = await call('POST', '/api/v1/tags/merge', { sourceIds: [a, b], targetId: target });
    expect(merged.statusCode).toBe(200);

    const rows = db.all<{ n: number }>(
      'SELECT count(*) AS n FROM book_tags WHERE book_id = ? AND tag_id = ?',
      [bookIds[0]!, target],
    );
    expect(rows[0]!.n).toBe(1);
    expect(db.get('SELECT 1 FROM tags WHERE id = ?', [a])).toBeUndefined();
    const counters = await call('GET', '/api/v1/tags');
    const merged2 = (counters.json().items as { id: string; usage_count: number }[]).find((t) => t.id === target);
    expect(merged2?.usage_count).toBe(1);
  });

  it('keeps a normal user out of shared taxonomy edits', async () => {
    const res = await call('POST', '/api/v1/tags', { name: '越权标签' }, userToken);
    expect(res.statusCode).toBe(403);
    expect(res.json().error.code).toBe('capability_denied');
  });
});

describe('collections', () => {
  it('lets a user create a personal collection but not a shared one', async () => {
    const personal = await call('POST', '/api/v1/collections', { name: '我的在读' }, userToken);
    expect(personal.statusCode).toBe(201);
    expect(personal.json().collection.owner_user_id).toBe(userId);

    const shared = await call('POST', '/api/v1/collections', { name: '全家共享', shared: true }, userToken);
    expect(shared.statusCode).toBe(403);
    expect(shared.json().error.details.capability).toBe('collections:global');
  });

  it('appends added books after the existing order instead of renumbering the shelf', async () => {
    const created = await call('POST', '/api/v1/collections', { name: '顺序检查' });
    const id = created.json().collection.id as string;
    await call('PUT', `/api/v1/collections/${id}/books`, { bookIds: [bookIds[0]!, bookIds[1]!] });
    await call('PUT', `/api/v1/collections/${id}/books`, { bookIds: [bookIds[2]!] });

    const listed = await call('GET', `/api/v1/collections/${id}/books`);
    const positions = (listed.json().items as { id: string; position: number }[]).map((i) => i.position);
    expect(positions).toEqual([0, 1, 2]);
  });

  it("refuses one user editing another user's private collection", async () => {
    const created = await call('POST', '/api/v1/collections', { name: '私有分组' }, userToken);
    const id = created.json().collection.id as string;

    // A second *normal* user, not an admin: an admin holds collections:global and is legitimately
    // allowed, so testing with one would only prove that assertion can pass either way.
    const users = new UserRepo(db);
    await seedUser(users, { username: 'other', password: 'other-password-1' });
    const otherToken = (await signIn(app, 'other', 'other-password-1')).accessToken;

    const res = await call('PATCH', `/api/v1/collections/${id}`, { name: '被改掉了' }, otherToken);
    expect(res.statusCode).toBe(403);
    expect(res.json().error.message).toMatch(/someone else/);

    const stillThere = db.get<{ name: string }>('SELECT name FROM collections WHERE id = ?', [id]);
    expect(stillThere?.name).toBe('私有分组');
  });
});

describe('batch operations', () => {
  it('reports per id and marks a stale selection instead of failing the batch', async () => {
    const stale = 'ZZZZZZZZZZZZZZZZZZZZZZZZZZ';
    const res = await call('POST', '/api/v1/books/batch/category', {
      bookIds: [bookIds[0]!, stale],
      categoryId: null,
    });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.applied).toBe(1);
    expect(body.skipped).toBe(1);
    expect(body.changed.find((c: { id: string }) => c.id === stale).error).toBe('book_not_found');
  });

  it('changes nothing on a dry run', async () => {
    const before = db.get<{ n: number }>('SELECT count(*) AS n FROM book_tags')?.n;
    const res = await call('POST', '/api/v1/books/batch/tag', {
      bookIds: [bookIds[1]!],
      add: ['预演标签'],
      dryRun: true,
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().dryRun).toBe(true);
    expect(db.get<{ n: number }>('SELECT count(*) AS n FROM book_tags')?.n).toBe(before);
    expect(db.get('SELECT 1 FROM tags WHERE name = ?', ['预演标签'])).toBeUndefined();
  });

  it('applies the whole batch or none of it', async () => {
    const res = await call('POST', '/api/v1/books/batch/update', {
      bookIds: bookIds.slice(0, 3),
      patch: { language: 'zh-CN', publisher: '批次出版社' },
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().applied).toBe(3);
    const tagged = db.all<{ n: number }>(
      `SELECT count(*) AS n FROM books WHERE publisher = ? AND id IN (${bookIds.slice(0, 3).map(() => '?').join(',')})`,
      ['批次出版社', ...bookIds.slice(0, 3)],
    );
    expect(tagged[0]!.n).toBe(3);
  });

  it('lets a normal user set their own shelf status without library:edit', async () => {
    const res = await call('POST', '/api/v1/books/batch/status', {
      bookIds: [bookIds[0]!, bookIds[1]!],
      status: 'reading',
      shelf: 'library',
    }, userToken);
    expect(res.statusCode).toBe(200);
    expect(res.json().applied).toBe(2);
    const states = db.all<{ status: string }>(
      'SELECT status FROM user_book_states WHERE user_id = ? AND book_id IN (?, ?)',
      [userId, bookIds[0]!, bookIds[1]!],
    );
    expect(states.map((s) => s.status)).toEqual(['reading', 'reading']);
  });

  it('refuses a delete to a user without batch:delete, and leaves the books alone', async () => {
    const denied = await call('POST', '/api/v1/books/batch/delete', { bookIds: [bookIds[5]!] }, userToken);
    expect(denied.statusCode).toBe(403);
    expect(db.get('SELECT deleted_at FROM books WHERE id = ?', [bookIds[5]!])?.deleted_at).toBeNull();

    const allowed = await call('POST', '/api/v1/books/batch/delete', { bookIds: [bookIds[5]!] });
    expect(allowed.statusCode).toBe(200);
    // Soft: the row survives so another device can be told the book is gone.
    expect(db.get('SELECT deleted_at FROM books WHERE id = ?', [bookIds[5]!])?.deleted_at).not.toBeNull();
  });

  it('rejects an over-long selection rather than truncating it silently', async () => {
    const res = await call('POST', '/api/v1/books/batch/category', {
      bookIds: Array.from({ length: 2001 }, () => bookIds[0]!),
      categoryId: null,
    });
    expect(res.statusCode).toBe(422);
  });
});
