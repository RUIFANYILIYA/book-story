import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Config } from '../../config.ts';
import type { Db } from '../../db/index.ts';
import { parseWith, csvList, booleanQuery } from '../../http/validate.ts';
import { AppError } from '../../errors.ts';
import { createGuards } from '../../auth/guard.ts';
import { SORT_KEYS, type CursorCodec, type SortKey } from '../../http/pagination.ts';
import { BookSearch, type BrowseFilters } from '../../library/search.ts';
import { BookRepo, type BookRow } from '../../db/repositories/book.repo.ts';
import { BlobRepo } from '../../db/repositories/blob.repo.ts';
import { placeholderCoverSvg, SVG_MEDIA_TYPE } from '../../library/covers.ts';
import type { SessionService } from '../../auth/sessions.ts';

/**
 * Only the column list is dynamic, and it comes from this literal object rather than from the
 * query string. A sort key that reached SQL as text would be an injection no parameter binding
 * can prevent, which is why `SORT_KEYS` is a lookup and not a format string.
 */
const QuerySchema = z.object({
  q: z.string().trim().max(200).optional(),
  format: csvList(12).optional(),
  tag: csvList(20).optional(),
  notTag: csvList(20).optional(),
  category: z.string().trim().max(400).optional(),
  seriesId: z.string().trim().max(26).optional(),
  collection: z.string().trim().max(26).optional(),
  contributor: z.string().trim().max(26).optional(),
  language: z.string().trim().max(12).optional(),
  status: z.enum(['none', 'reading', 'finished', 'abandoned', 'on_hold']).optional(),
  needsReview: booleanQuery.optional(),
  missingCover: booleanQuery.optional(),
  minYear: z.coerce.number().int().min(800).max(2499).optional(),
  maxYear: z.coerce.number().int().min(800).max(2499).optional(),
  sort: z.enum(Object.keys(SORT_KEYS) as [SortKey, ...SortKey[]]).default('addedAt'),
  order: z.enum(['asc', 'desc']).default('desc'),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  cursor: z.string().max(400).optional(),
}).refine((query) => query.sort !== 'shelf' || query.collection !== undefined, {
  // "Position on the shelf" is a column of `collection_books`, and that table is only joined when a
  // collection is named. Sorting by it otherwise would be a 500 from an unjoined alias.
  path: ['sort'],
  message: 'sort=shelf requires a collection',
});

interface SummaryDto {
  id: string;
  title: string;
  authors: string[];
  seriesName: string | null;
  seriesIndex: number | null;
  year: number | null;
  language: string | null;
  /**
   * The shelf the book sits in, or `null` when it is unfiled. The app syncs its shelves from this
   * field: without it a client could pull the category tree and still not know which book goes
   * where.
   */
  categoryId: string | null;
  formats: string[];
  hasCover: boolean;
  coverUrl: string | null;
  /**
   * When the cover bytes last changed — a fresh ingest, a redraw after a metadata edit, or a
   * replacement. Clients cache the image by URL, and the URL is stable, so this is the stamp
   * they append as a cache-buster: without it an edited title keeps showing the old tile.
   */
  coverUpdatedAt: string | null;
  updatedAt: string;
  contentUpdatedAt: string;
  sizeBytes: number;
  fileCount: number;
  needsReview: boolean;
  my: { status: string | null; percent: number | null; lastReadAt: string | null };
}

export function registerBookRoutes(
  app: FastifyInstance,
  deps: { db: Db; config: Config; codec: CursorCodec; session: SessionService },
): void {
  const { db, config, codec, session } = deps;
  const guards = createGuards(session);
  const pre = { preHandler: [guards.authenticate, guards.require('library:read')] };
  const search = new BookSearch(db, codec);
  const books = new BookRepo(db);

  app.get('/api/v1/books', pre, async (request) => {
    const query = parseWith(QuerySchema, request.query, 'query');
    const auth = request.auth!;

    const filters: BrowseFilters = {
      q: query.q ?? null,
      format: query.format,
      tag: query.tag,
      notTag: query.notTag,
      categoryPath: query.category ?? null,
      seriesId: query.seriesId ?? null,
      collectionId: query.collection ?? null,
      contributorId: query.contributor ?? null,
      language: query.language ?? null,
      status: query.status ?? null,
      needsReview: query.needsReview ?? null,
      missingCover: query.missingCover ?? null,
      minYear: query.minYear ?? null,
      maxYear: query.maxYear ?? null,
    };

    const page = search.browse(auth.user.id, {
      filters,
      sort: query.sort,
      order: query.order,
      limit: query.limit,
      cursor: query.cursor ?? null,
    });

    return {
      items: page.items.map(toSummary),
      nextCursor: page.nextCursor,
      hasMore: page.hasMore,
      limit: query.limit,
      sort: query.sort,
      order: query.order,
    };
  });

  app.get('/api/v1/books/facets', pre, async () => search.facets());

  app.get('/api/v1/books/:id', pre, async (request) => {
    const { id } = parseWith(z.object({ id: z.string().regex(/^[0-9A-HJKMNP-TV-Z]{26}$/u) }), request.params, 'params');
    const book = books.findById(id);
    if (!book) {
      throw new AppError('book_not_found', 'Book not found');
    }
    const state = db.get<Record<string, unknown>>(
      'SELECT * FROM user_book_states WHERE user_id = ? AND book_id = ?',
      [request.auth!.user.id, id],
    );
    return {
      book: toDetail(book, db),
      my: state ?? null,
      files: db
        .all<Record<string, unknown> & { drm: number; is_primary: number }>(
          `SELECT id, format, media_type, filename, size_bytes, sha256, charset, drm, is_primary, status, page_count
             FROM book_files WHERE book_id = ? ORDER BY is_primary DESC, created_at`,
          [id],
        )
        .map((file) => ({ ...file, drm: file.drm === 1, is_primary: file.is_primary === 1 })),
    };
  });

  app.patch('/api/v1/books/:id', { preHandler: [guards.authenticate, guards.require('library:edit')] }, async (request) => {
    const { id } = parseWith(z.object({ id: z.string().regex(/^[0-9A-HJKMNP-TV-Z]{26}$/u) }), request.params, 'params');
    const body = parseWith(
      z.object({
        title: z.string().trim().min(1).max(400).optional(),
        description: z.string().max(20000).nullish(),
        publisher: z.string().trim().max(300).nullish(),
        publishedYear: z.number().int().min(800).max(2499).nullish(),
        language: z.string().trim().max(12).nullish(),
        isbn: z.string().trim().max(32).nullish(),
        categoryId: z.string().trim().max(26).nullish(),
        seriesName: z.string().trim().max(300).nullish(),
        seriesIndex: z.number().min(0).max(9999).nullish(),
        tags: z.array(z.string().trim().min(1).max(100)).max(100).optional(),
        /** Display-order names. Absent means "not editing authors"; an empty array is a real edit
         *  that clears them, which is why this cannot be collapsed into `tags`-style falsiness. */
        authors: z.array(z.string().trim().min(1).max(300)).max(40).nullish(),
        clearNeedsReview: z.boolean().optional(),
      }),
      request.body,
      'body',
    );

    const existing = books.findById(id);
    if (!existing) {
      throw new AppError('book_not_found', 'Book not found');
    }

    const sets: string[] = [];
    const values: (string | number | null)[] = [];
    for (const [column, value] of [
      ['title', body.title],
      ['description', body.description],
      ['publisher', body.publisher],
      ['published_year', body.publishedYear],
      ['language', body.language],
      ['isbn', body.isbn],
      ['category_id', body.categoryId],
      ['series_index', body.seriesIndex],
    ] as const) {
      if (value !== undefined) {
        sets.push(`${column} = ?`);
        values.push(value as string | number | null);
      }
    }
    if (body.title !== undefined) {
      sets.push('title_norm = ?');
      values.push(normalize(body.title));
    }
    if (body.seriesName !== undefined) {
      sets.push('series_id = ?');
      values.push(body.seriesName === null ? null : books.ensureSeries(body.seriesName));
    }
    if (body.clearNeedsReview) {
      sets.push('needs_review = 0');
    }
    // Manual edits win over any later re-parse, and this is what records that intent.
    const pinned = new Set<string>(readPinned(existing.pinned_fields_json));
    for (const field of ['title', 'description', 'publisher', 'publishedYear', 'language', 'isbn'] as const) {
      if (body[field] !== undefined) pinned.add(field === 'publishedYear' ? 'published_year' : field);
    }
    if (body.authors !== undefined) pinned.add('authors');
    sets.push('pinned_fields_json = ?');
    values.push(JSON.stringify([...pinned]));
    sets.push('updated_at = ?');
    values.push(new Date().toISOString());

    db.run(`UPDATE books SET ${sets.join(', ')} WHERE id = ?`, [...values, id]);

    if (body.authors !== undefined) {
      books.setAuthors(id, body.authors ?? []);
      // The sort key embeds the first author, so it has to follow the name rather than keep pointing
      // at whoever the file claimed; otherwise the shelf still orders the book under the old author.
      const first = books.authorsOf(id)[0] ?? null;
      const title = body.title ?? existing.title;
      db.run('UPDATE books SET sort_key = ? WHERE id = ?', [first ? `${first} — ${title}` : title, id]);
    }

    if (body.tags) {
      db.run('DELETE FROM book_tags WHERE book_id = ?', [id]);
      books.addTags(id, body.tags, request.auth!.user.id);
    }
    books.refreshSearchText(id);

    // A generated cover is a picture of the metadata — title and author are drawn into it at
    // ingest time — so editing either must redraw it, or the shelf keeps showing the old ones.
    if (body.title !== undefined || body.authors !== undefined) {
      await regeneratePlaceholderCover(db, config, id);
    }

    return { book: toDetail(books.findById(id)!, db) };
  });

  /**
   * Soft delete, one book.
   *
   * The same tombstone the batch route writes, for the case that needs no selection: the console's
   * per-book action, and any client that already holds the id. Gated on `book:delete` rather than
   * `batch:delete` — the capability list has carried that entry from the start with nothing using
   * it, and removing one book is the smaller grant: a trusted non-admin who may tidy the shelf
   * should not need the bulk one for it.
   *
   * A book that is already gone answers 404 rather than an idempotent 204, because the two cases
   * a caller can act on are different: "it was there and now is not" versus "it was never there",
   * and collapsing them would let a stale console tab believe it had just deleted something.
   *
   * The bytes behave exactly as after a batch delete: the file row stays `ready` and readable for
   * the retention window, the blob is retired by the sweeper afterwards, and `/sync/delta` hands
   * the tombstone to every device.
   */
  app.delete('/api/v1/books/:id', { preHandler: [guards.authenticate, guards.require('book:delete')] }, async (request, reply) => {
    const { id } = parseWith(z.object({ id: z.string().regex(/^[0-9A-HJKMNP-TV-Z]{26}$/u) }), request.params, 'params');
    // findById filters tombstones, so this is also the "already deleted" answer.
    if (!books.findById(id)) {
      throw new AppError('book_not_found', 'Book not found');
    }
    const now = new Date().toISOString();
    db.run('UPDATE books SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL', [now, now, id]);
    return reply.status(204).send();
  });
}

function toSummary(row: Record<string, unknown>): SummaryDto {
  return {
    id: String(row.id),
    title: String(row.title),
    authors: String(row.authors ?? '').split(' / ').filter(Boolean),
    seriesName: (row.series_name as string | null) ?? null,
    seriesIndex: (row.series_index as number | null) ?? null,
    year: (row.published_year as number | null) ?? null,
    language: (row.language as string | null) ?? null,
    categoryId: (row.category_id as string | null) ?? null,
    formats: String(row.formats ?? '').split(',').filter(Boolean),
    hasCover: row.has_cover === 1,
    coverUrl: row.has_cover === 1 ? `/api/v1/covers/${row.id}` : null,
    coverUpdatedAt: (row.cover_updated_at as string | null) ?? null,
    updatedAt: String(row.updated_at ?? row.content_updated_at),
    contentUpdatedAt: String(row.content_updated_at),
    sizeBytes: Number(row.storage_bytes ?? 0),
    fileCount: Number(row.file_count ?? 0),
    needsReview: row.needs_review === 1,
    my: {
      status: (row.my_status as string | null) ?? null,
      percent: (row.my_percent as number | null) ?? null,
      lastReadAt: (row.my_last_read as string | null) ?? null,
    },
  };
}

function toDetail(book: BookRow, db: Db): Record<string, unknown> {
  const tags = db
    .all<{ name: string }>(
      `SELECT t.name FROM book_tags bt JOIN tags t ON t.id = bt.tag_id WHERE bt.book_id = ? ORDER BY t.name`,
      [book.id],
    )
    .map((r) => r.name);
  const collections = db.all(
    `SELECT c.id, c.name, c.owner_user_id, cb.position FROM collection_books cb
       JOIN collections c ON c.id = cb.collection_id
      WHERE cb.book_id = ? AND c.deleted_at IS NULL`,
    [book.id],
  );
  // SQLite has no boolean storage class, and spreading the row here would answer `needs_review: 1`
  // where the contract says boolean. Clients that parse strictly (the Android app's serializer does)
  // fail on that rather than coercing, so the conversion happens at the wire boundary.
  return {
    ...book,
    needs_review: book.needs_review === 1,
    has_cover: book.has_cover === 1,
    authors: new BookRepo(db).authorsOf(book.id),
    tags,
    collections,
  };
}

function normalize(value: string): string {
  return value.normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}

/**
 * Re-renders a generated placeholder cover from the book's current title and author.
 *
 * The tile is drawn once, at ingest, from whatever the metadata said then — so editing the
 * title or the author through the console leaves it advertising the old ones until something
 * redraws it. Only placeholders are touched: an embedded cover is a picture with no text in
 * it, and a reader's own upload is theirs, not the server's to regenerate. The superseded
 * tile is released the same way the reader-upload route releases the override it replaces —
 * identical bytes dedupe to the same blob, so re-rendering unchanged text must not drop its
 * own refcount to zero — and an orphaned tile is reclaimed by the sweeper on the usual clock.
 */
async function regeneratePlaceholderCover(db: Db, config: Config, bookId: string): Promise<void> {
  const book = db.get<{ cover_blob_id: string | null }>(
    'SELECT cover_blob_id FROM books WHERE id = ? AND deleted_at IS NULL',
    [bookId],
  );
  if (!book?.cover_blob_id) return;

  const blobs = new BlobRepo(db, config.libraryDir);
  const current = blobs.byId(book.cover_blob_id);
  if (!current || current.format !== 'cover-placeholder') return;

  const row = db.get<{ title: string }>('SELECT title FROM books WHERE id = ?', [bookId]);
  const author = new BookRepo(db).authorsOf(bookId).join(', ').trim() || null;
  // The seed only picks the colour; the file the tile was first drawn from keeps it stable
  // across re-renders, so fixing a typo does not also repaint the book.
  const seed =
    db
      .get<{ blob_id: string }>(
        'SELECT blob_id FROM book_files WHERE book_id = ? ORDER BY is_primary DESC, created_at LIMIT 1',
        [bookId],
      )
      ?.blob_id ?? bookId;

  const svg = placeholderCoverSvg({ title: row?.title ?? '未命名', author, seed });
  const stored = await blobs.putBuffer(svg, SVG_MEDIA_TYPE, 'cover-placeholder');
  if (stored.id !== current.id) blobs.decrement(current.id);
  new BookRepo(db).setCover(bookId, stored.id);
}

function readPinned(json: string | undefined): string[] {
  if (!json) return [];
  try {
    const parsed: unknown = JSON.parse(json);
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : [];
  } catch {
    return [];
  }
}
