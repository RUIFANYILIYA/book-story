import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parseWith, idSchema, idListSchema, MAX_BATCH_SIZE } from '../../http/validate.ts';
import { AppError } from '../../errors.ts';
import { createGuards } from '../../auth/guard.ts';
import { TaxonomyRepo } from '../../db/repositories/taxonomy.repo.ts';
import type { Db } from '../../db/index.ts';
import type { SessionService } from '../../auth/sessions.ts';

/**
 * Batch shelf maintenance — the "select thirty books and file them" operation.
 *
 * Two properties matter more than speed here.
 *
 * One transaction for the whole batch. A partial batch is the worst outcome for the user: they
 * selected a set, the request failed at item 17, and now they cannot tell what applied without
 * re-reading the shelf. `db.tx` covers the whole unit, so it either happened or it did not.
 *
 * A per-id result rather than a bare count. "42 changed" cannot answer "which of these did not",
 * and a book that was deleted by another device between selection and submission is expected, not
 * exceptional — the caller needs to know exactly which ids to drop from its selection.
 */

const BatchBody = z.object({
  bookIds: idListSchema.max(MAX_BATCH_SIZE),
  dryRun: z.boolean().default(false),
});

interface BatchOutcome {
  requested: number;
  changed: { id: string; ok: boolean; error?: string }[];
  applied: number;
  skipped: number;
  dryRun: boolean;
}

function summarise(
  requested: string[],
  results: { id: string; ok: boolean; error?: string }[],
  dryRun: boolean,
): BatchOutcome {
  return {
    requested: requested.length,
    changed: results,
    applied: results.filter((r) => r.ok).length,
    skipped: results.filter((r) => !r.ok).length,
    dryRun,
  };
}

export function registerBatchRoutes(app: FastifyInstance, deps: { db: Db; session: SessionService }): void {
  const { db } = deps;
  const taxonomy = new TaxonomyRepo(db);
  const guards = createGuards(deps.session);
  const read = { preHandler: [guards.authenticate, guards.require('library:read')] };
  const edit = { preHandler: [guards.authenticate, guards.require('library:edit')] };

  /** Ids that exist and are not tombstoned, so a stale selection reports skips instead of failing. */
  const liveIds = (ids: string[]): Set<string> =>
    new Set(
      db
        .all<{ id: string }>(
          `SELECT id FROM books WHERE deleted_at IS NULL AND id IN (${ids.map(() => '?').join(',')})`,
          ids,
        )
        .map((r) => r.id),
    );

  app.post('/api/v1/books/batch/tag', edit, async (request) => {
    const body = parseWith(
      BatchBody.extend({
        add: z.array(z.string().trim().min(1).max(100)).max(100).default([]),
        remove: z.array(idSchema).max(100).default([]),
      }),
      request.body,
      'body',
    );
    const live = liveIds(body.bookIds);
    if (body.dryRun) {
      return summarise(body.bookIds, body.bookIds.map((id) => ({ id, ok: live.has(id) })), true);
    }

    const results = db.tx(() => {
      const tagIds = body.add.map((name) => taxonomy.ensureTag(name).id);
      const now = new Date().toISOString();
      const actor = request.auth?.user.id ?? null;
      return body.bookIds.map((id) => {
        if (!live.has(id)) return { id, ok: false, error: 'book_not_found' };
        for (const tagId of tagIds) {
          db.run(
            'INSERT OR IGNORE INTO book_tags (book_id, tag_id, added_by, added_at) VALUES (?, ?, ?, ?)',
            [id, tagId, actor, now],
          );
        }
        for (const tagId of body.remove) {
          db.run('DELETE FROM book_tags WHERE book_id = ? AND tag_id = ?', [id, tagId]);
        }
        return { id, ok: true };
      });
    });
    taxonomy.refreshTagCounters();
    for (const id of body.bookIds) if (live.has(id)) refreshSearchText(db, id);
    return summarise(body.bookIds, results, false);
  });

  app.post('/api/v1/books/batch/collection', edit, async (request) => {
    const body = parseWith(
      BatchBody.extend({
        add: z.array(idSchema).max(50).default([]),
        remove: z.array(idSchema).max(50).default([]),
      }),
      request.body,
      'body',
    );
    if (body.dryRun) return summarise(body.bookIds, body.bookIds.map((id) => ({ id, ok: true })), true);

    const results = db.tx(() =>
      body.bookIds.map((id) => {
        try {
          for (const collectionId of body.add) {
            const collection = taxonomy.collectionById(collectionId);
            if (!collection) return { id, ok: false, error: 'collection_not_found' };
            taxonomy.addBooks(collectionId, [id], request.auth?.user.id ?? null);
          }
          for (const collectionId of body.remove) taxonomy.removeBooks(collectionId, [id]);
          return { id, ok: true };
        } catch (err) {
          return { id, ok: false, error: err instanceof Error ? err.message : 'failed' };
        }
      }),
    );
    return summarise(body.bookIds, results, false);
  });

  app.post('/api/v1/books/batch/category', edit, async (request) => {
    const body = parseWith(
      BatchBody.extend({ categoryId: idSchema.nullable() }),
      request.body,
      'body',
    );
    if (body.categoryId !== null && !taxonomy.categoryById(body.categoryId)) {
      throw new AppError('category_not_found', 'Category not found');
    }
    const live = liveIds(body.bookIds);
    if (body.dryRun) return summarise(body.bookIds, body.bookIds.map((id) => ({ id, ok: live.has(id) })), true);

    const results = db.tx(() => {
      const now = new Date().toISOString();
      return body.bookIds.map((id) => {
        if (!live.has(id)) return { id, ok: false, error: 'book_not_found' };
        db.run('UPDATE books SET category_id = ?, updated_at = ? WHERE id = ?', [body.categoryId, now, id]);
        return { id, ok: true };
      });
    });
    return summarise(body.bookIds, results, false);
  });

  /**
   * Personal shelf/status changes. Not gated on library:edit, because this writes only the
   * caller's own `user_book_states` row — the same rule that lets anyone mark their own book as
   * finished without being able to touch the shared record.
   */
  app.post('/api/v1/books/batch/status', read, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const body = parseWith(
      BatchBody.extend({
        shelf: z.enum(['library', 'wishlist']).optional(),
        status: z.enum(['none', 'reading', 'finished', 'abandoned', 'on_hold']).optional(),
      }),
      request.body,
      'body',
    );
    if (body.shelf === undefined && body.status === undefined) {
      throw new AppError('bad_request', 'Provide a shelf or a status to change');
    }
    const live = liveIds(body.bookIds);
    if (body.dryRun) return summarise(body.bookIds, body.bookIds.map((id) => ({ id, ok: live.has(id) })), true);

    const now = new Date().toISOString();
    const results = db.tx(() =>
      body.bookIds.map((id) => {
        if (!live.has(id)) return { id, ok: false, error: 'book_not_found' };
        // The rev counter is per user and allocated inside this transaction, so an offline client
        // can order its own writes against the server's without a separate sequence table.
        const nextRev =
          (db.get<{ n: number | null }>(
            'SELECT max(rev) AS n FROM user_book_states WHERE user_id = ?',
            [auth.user.id],
          )?.n ?? 0) + 1;
        db.run(
          `INSERT INTO user_book_states (user_id, book_id, shelf, status, created_at, updated_at, rev)
           VALUES (?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT (user_id, book_id) DO UPDATE SET
             shelf = coalesce(excluded.shelf, user_book_states.shelf),
             status = coalesce(excluded.status, user_book_states.status),
             updated_at = excluded.updated_at,
             rev = excluded.rev`,
          [
            auth.user.id,
            id,
            body.shelf ?? 'library',
            body.status ?? 'none',
            now,
            now,
            nextRev,
          ],
        );
        return { id, ok: true };
      }),
    );
    return summarise(body.bookIds, results, false);
  });

  app.post('/api/v1/books/batch/update', edit, async (request) => {
    const body = parseWith(
      BatchBody.extend({
        patch: z.object({
          language: z.string().trim().max(12).nullish(),
          publisher: z.string().trim().max(300).nullish(),
          publishedYear: z.number().int().min(800).max(2499).nullish(),
          clearNeedsReview: z.boolean().optional(),
        }),
      }),
      request.body,
      'body',
    );
    const live = liveIds(body.bookIds);
    if (body.dryRun) return summarise(body.bookIds, body.bookIds.map((id) => ({ id, ok: live.has(id) })), true);

    const results = db.tx(() => {
      const now = new Date().toISOString();
      return body.bookIds.map((id) => {
        if (!live.has(id)) return { id, ok: false, error: 'book_not_found' };
        const sets: string[] = [];
        const values: (string | number | null)[] = [];
        for (const [column, value] of [
          ['language', body.patch.language],
          ['publisher', body.patch.publisher],
          ['published_year', body.patch.publishedYear],
        ] as const) {
          if (value !== undefined) {
            sets.push(`${column} = ?`);
            values.push(value as string | number | null);
          }
        }
        if (body.patch.clearNeedsReview) sets.push('needs_review = 0');
        if (sets.length > 0) {
          db.run(`UPDATE books SET ${sets.join(', ')}, updated_at = ? WHERE id = ?`, [...values, now, id]);
        }
        return { id, ok: true };
      });
    });
    for (const id of body.bookIds) if (live.has(id)) refreshSearchText(db, id);
    return summarise(body.bookIds, results, false);
  });

  /**
   * Soft delete. Files stay readable for the retention window so another device's in-flight
   * download is not corrupted by the delete, and /sync/delta hands the tombstone to clients.
   */
  app.post('/api/v1/books/batch/delete', { preHandler: [guards.authenticate, guards.require('batch:delete')] }, async (request) => {
    const body = parseWith(BatchBody, request.body, 'body');
    const live = liveIds(body.bookIds);
    if (body.dryRun) return summarise(body.bookIds, body.bookIds.map((id) => ({ id, ok: live.has(id) })), true);

    const now = new Date().toISOString();
    const results = db.tx(() =>
      body.bookIds.map((id) => {
        if (!live.has(id)) return { id, ok: false, error: 'book_not_found' };
        db.run('UPDATE books SET deleted_at = ?, updated_at = ? WHERE id = ?', [now, now, id]);
        return { id, ok: true };
      }),
    );
    return summarise(body.bookIds, results, false);
  });
}

/** Kept local: the search haystack must be rebuilt whenever tags or metadata change. */
function refreshSearchText(db: Db, bookId: string): void {
  const book = db.get<{ title: string; publisher: string | null; description: string | null; series: string | null }>(
    `SELECT b.title, b.publisher, b.description, s.name AS series
       FROM books b LEFT JOIN series s ON s.id = b.series_id WHERE b.id = ?`,
    [bookId],
  );
  if (!book) return;
  const authors = db
    .all<{ name: string }>(
      `SELECT c.name FROM book_contributors bc JOIN contributors c ON c.id = bc.contributor_id
        WHERE bc.book_id = ? ORDER BY bc.position`,
      [bookId],
    )
    .map((r) => r.name);
  const tags = db
    .all<{ name: string }>(
      'SELECT t.name FROM book_tags bt JOIN tags t ON t.id = bt.tag_id WHERE bt.book_id = ?',
      [bookId],
    )
    .map((r) => r.name);
  const haystack = [book.title, ...authors, ...tags, book.series, book.publisher, book.description]
    .filter(Boolean)
    .join(' ');
  db.run('UPDATE books SET search_text = ? WHERE id = ?', [
    haystack.normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim(),
    bookId,
  ]);
}
