import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { BookRepo } from '../src/db/repositories/book.repo.ts';
import type { ContributorHint, ParsedMetadata } from '../src/metadata/types.ts';
import { emptyMetadata } from '../src/metadata/types.ts';
import { newId } from '../src/ids.ts';
import { insertBook } from './helpers/db.ts';
import { bearer, buildTestApp, seedUser, signIn, type TestApp } from './helpers/app.ts';

/**
 * Editing the author.
 *
 * The admin page has had this input box since M6 and it has never once worked: the detail response
 * carried no `authors`, so the box always rendered empty, and the save call never read it. Fixing
 * only the box would be worse than useless, though — a re-parse wrote contributors
 * unconditionally, so the next import of a messy file would silently undo whatever was typed. Both
 * halves are tested here.
 */

let ctx: TestApp;
let editor: string;
let reader: string;

const ID = 'a'.repeat(64);

beforeAll(async () => {
  ctx = await buildTestApp();
  await seedUser(ctx.users, { username: 'editor', password: 'pw-editor-123456', role: 'admin' });
  await seedUser(ctx.users, { username: 'reader', password: 'pw-reader-123456' });
  editor = (await signIn(ctx.app, 'editor', 'pw-editor-123456')).accessToken;
  reader = (await signIn(ctx.app, 'reader', 'pw-reader-123456')).accessToken;
});

afterAll(async () => {
  await ctx.app.close();
});

let seq = 0;
/** A book with the given authors, through the same repository the ingest path uses.
 *  Real ULID ids, because the route validates the `:id` param against a 26-character pattern. */
function shelvedWith(authors: string[], extra: { translator?: string } = {}): string {
  const id = newId();
  seq += 1;
  insertBook(ctx.db, id, { title: `作者测试 ${seq}` });
  const repo = new BookRepo(ctx.db);
  const people: ContributorHint[] = authors.map((name) => ({ name, role: 'author' }));
  if (extra.translator) people.push({ name: extra.translator, role: 'translator' });
  repo.setContributors(id, people);
  return id;
}

// `return await` rather than returning the inject chain: the chain's own type is not what the
// response ends up being, and the helpers need to hand back something with `statusCode` on it.
async function getDetail(id: string, token = editor) {
  return await ctx.app.inject({ method: 'GET', url: `/api/v1/books/${id}`, headers: bearer(token) });
}

async function patchBook(id: string, body: Record<string, unknown>, token = editor) {
  return await ctx.app.inject({
    method: 'PATCH',
    url: `/api/v1/books/${id}`,
    headers: bearer(token),
    payload: body,
  });
}

const readAuthors = (id: string) => new BookRepo(ctx.db).authorsOf(id);
const rolesOf = (id: string) =>
  ctx.db
    .all<{ role: string; name: string }>(
      `SELECT bc.role, c.name FROM book_contributors bc JOIN contributors c ON c.id = bc.contributor_id
        WHERE bc.book_id = ? ORDER BY bc.role, bc.position`,
      [id],
    )
    .map((r) => `${r.role}:${r.name}`);

/** A re-parse of the same file, which is what `keepManualAuthors` has to survive. */
function reparse(id: string, metaAuthors: string[], translator: string | null = null): void {
  const contributors: ContributorHint[] = metaAuthors.map((name) => ({ name, role: 'author' }));
  if (translator) contributors.push({ name: translator, role: 'translator' });
  const meta: ParsedMetadata = { ...emptyMetadata('txt', 'txt'), title: '重新解析的标题', contributors };
  new BookRepo(ctx.db).upsertFromMetadata({
    meta,
    sha256: ID,
    filename: 'novel.txt',
    userId: null,
    targetBookId: id,
    mode: 'attach',
  });
}

describe('PATCH /books/:id authors', () => {
  it('writes, reads back, and moves the sort key with it', async () => {
    const id = shelvedWith(['刘慈欣']);

    const res = await patchBook(id, { authors: ['余华', '史铁生'] });

    expect(res.statusCode).toBe(200);
    expect(res.json().book.authors).toEqual(['余华', '史铁生']);
    expect(readAuthors(id)).toEqual(['余华', '史铁生']);
    // The shelf sorts by sort_key, which embeds the first author — leaving the old name there would
    // sort the book under an author nobody chose.
    expect(
      ctx.db.get<{ sort_key: string }>('SELECT sort_key FROM books WHERE id = ?', [id])?.sort_key,
    ).toContain('余华');
    expect(
      ctx.db.get<{ pinned_fields_json: string }>('SELECT pinned_fields_json FROM books WHERE id = ?', [id])
        ?.pinned_fields_json,
    ).toContain('authors');
  });

  it('leaves authors alone when the field is absent', async () => {
    const id = shelvedWith(['刘慈欣']);

    await patchBook(id, { description: '只是改简介' });

    expect(readAuthors(id)).toEqual(['刘慈欣']);
  });

  it('treats an empty array as a real edit that clears them', async () => {
    const id = shelvedWith(['刘慈欣']);

    await patchBook(id, { authors: [] });

    expect(readAuthors(id)).toEqual([]);
    expect((await getDetail(id)).json().book.authors).toEqual([]);
  });

  it('replaces only the author role, so a declared translator survives', async () => {
    const id = shelvedWith(['刘慈欣'], { translator: '宋伟航' });

    await patchBook(id, { authors: ['阿城'] });

    expect(rolesOf(id)).toEqual(['author:阿城', 'translator:宋伟航']);
  });

  it('collapses a name typed twice into one author', async () => {
    const id = shelvedWith(['刘慈欣']);

    await patchBook(id, { authors: ['余华', ' 余华 ', '余华'] });

    expect(readAuthors(id)).toEqual(['余华']);
  });

  it('answers the same authors in the detail and in the browse list', async () => {
    const id = shelvedWith(['阿城', '史铁生']);

    const detail = (await getDetail(id)).json().book.authors as string[];
    const list = await ctx.app.inject({ method: 'GET', url: '/api/v1/books?limit=200', headers: bearer(editor) });
    const row = (list.json().items as { id: string; authors: string[] }[]).find((b) => b.id === id);

    // The app used to have to keep the list row alive to learn who wrote a book, because only the
    // summary carried authors. Both views must now agree.
    expect(row?.authors).toEqual(detail);
    expect(detail).toEqual(['阿城', '史铁生']);
  });

  it('refuses a reader without library:edit', async () => {
    const id = shelvedWith(['刘慈欣']);

    const res = await patchBook(id, { authors: ['随便'] }, reader);

    expect(res.statusCode).toBe(403);
    expect(readAuthors(id)).toEqual(['刘慈欣']);
  });
});

describe('a re-parse against a hand-edited author', () => {
  it('overwrites authors when nothing was pinned', async () => {
    const id = shelvedWith(['刘慈欣']);

    reparse(id, ['文件说的作者']);

    expect(readAuthors(id)).toEqual(['文件说的作者']);
  });

  it('keeps the manual names but still refreshes the translator', async () => {
    const id = shelvedWith(['刘慈欣'], { translator: '旧译者' });
    await patchBook(id, { authors: ['阿城'] });

    reparse(id, ['文件说的作者'], '新译者');

    expect(rolesOf(id)).toEqual(['author:阿城', 'translator:新译者']);
  });

  it('does not let the file put an author back after an explicit clear', async () => {
    const id = shelvedWith(['刘慈欣']);
    await patchBook(id, { authors: [] });

    reparse(id, ['文件说的作者']);

    expect(readAuthors(id)).toEqual([]);
  });
});
