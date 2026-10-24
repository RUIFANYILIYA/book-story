import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parseWith, idSchema } from '../../http/validate.ts';
import { AppError } from '../../errors.ts';
import { createGuards } from '../../auth/guard.ts';
import { newId } from '../../ids.ts';
import type { Db } from '../../db/index.ts';
import type { SessionService } from '../../auth/sessions.ts';

/**
 * Reading progress, bookmarks and delta sync.
 *
 * The whole design turns on one decision: the server owns a per-user monotonic `rev`, and clients
 * merge by it. Wall-clock timestamps from phones are not usable for ordering — they are wrong by
 * minutes, they go backwards when a user fixes their clock, and two devices disagree about "now",
 * so last-write-wins on a device clock silently discards the *newer* position. A server-assigned
 * counter is a total order and costs one query inside the same transaction that writes the row.
 *
 * Positions are stored per (user, file, device) rather than as a single value. Two devices sitting
 * in different chapters must not overwrite each other, and a position measured against one file
 * version is meaningless against another — which is why the merged state keeps the percentage and
 * only trusts a character offset that came from the same file.
 */

const TargetKind = z.enum(['char', 'page', 'percent', 'epub-cfi', 'cfml', 'locator', 'cbml']);

const ProgressBody = z.object({
  fileId: idSchema.nullish(),
  targetKind: TargetKind,
  targetValue: z.string().min(1).max(4000),
  percent: z.number().min(0).max(100).nullish(),
  status: z.enum(['none', 'reading', 'finished', 'abandoned', 'on_hold']).optional(),
  label: z.string().max(300).nullish(),
});

const BookmarkBody = z.object({
  id: idSchema.optional(),
  bookId: idSchema,
  fileId: idSchema.nullish(),
  targetKind: TargetKind,
  targetValue: z.string().min(1).max(4000),
  title: z.string().max(300).nullish(),
  excerpt: z.string().max(2000).nullish(),
  note: z.string().max(20000).nullish(),
  color: z.string().max(32).nullish(),
});

export function registerSyncRoutes(app: FastifyInstance, deps: { db: Db; session: SessionService }): void {
  const { db } = deps;
  const guards = createGuards(deps.session);
  const authed = { preHandler: [guards.authenticate] };

  /** Next rev for this user. Safe without a sequence table because writes are serialized. */
  const nextRev = (userId: string): number =>
    (db.get<{ n: number | null }>('SELECT max(rev) AS n FROM user_book_states WHERE user_id = ?', [userId])
      ?.n ?? 0) + 1;

  const nextBookmarkRev = (userId: string): number =>
    (db.get<{ n: number | null }>('SELECT max(rev) AS n FROM bookmarks WHERE user_id = ?', [userId])?.n ??
      0) + 1;

  app.put('/api/v1/progress/:bookId', authed, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { bookId } = parseWith(z.object({ bookId: idSchema }), request.params, 'params');
    const body = parseWith(ProgressBody, request.body, 'body');

    const book = db.get<{ id: string; file_count: number }>(
      'SELECT id, file_count FROM books WHERE id = ? AND deleted_at IS NULL',
      [bookId],
    );
    if (!book) throw new AppError('book_not_found', 'Book not found');
    if (body.fileId) {
      const file = db.get('SELECT 1 FROM book_files WHERE id = ? AND book_id = ?', [body.fileId, bookId]);
      if (!file) throw new AppError('file_not_found', 'That file does not belong to this book');
    }

    const now = new Date().toISOString();
    const rev = nextRev(auth.user.id);
    db.tx(() => {
      if (body.fileId) {
        db.run(
          `INSERT INTO reading_positions (id, user_id, book_id, file_id, device_id, target_kind, target_value, label, percent, created_at, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT (user_id, file_id, device_id) DO UPDATE SET
             target_kind = excluded.target_kind,
             target_value = excluded.target_value,
             label = excluded.label,
             percent = excluded.percent,
             updated_at = excluded.updated_at`,
          [
            newId(),
            auth.user.id,
            bookId,
            body.fileId,
            auth.device.id,
            body.targetKind,
            body.targetValue,
            body.label ?? null,
            body.percent ?? null,
            now,
            now,
          ],
        );
      }

      const status = body.status ?? (body.percent !== null && body.percent !== undefined && body.percent >= 99.5 ? 'finished' : 'reading');
      db.run(
        `INSERT INTO user_book_states (user_id, book_id, status, progress_percent, position_json,
                                       position_file_id, shelf, last_read_at, started_at, last_device_id,
                                       times_opened, created_at, updated_at, rev)
         VALUES (?, ?, ?, ?, ?, ?, 'library', ?, ?, ?, 1, ?, ?, ?)
         ON CONFLICT (user_id, book_id) DO UPDATE SET
           status = excluded.status,
           progress_percent = coalesce(excluded.progress_percent, user_book_states.progress_percent),
           position_json = coalesce(excluded.position_json, user_book_states.position_json),
           position_file_id = coalesce(excluded.position_file_id, user_book_states.position_file_id),
           last_read_at = excluded.last_read_at,
           started_at = coalesce(user_book_states.started_at, excluded.started_at),
           finished_at = CASE WHEN excluded.status = 'finished' THEN excluded.last_read_at ELSE user_book_states.finished_at END,
           last_device_id = excluded.last_device_id,
           times_opened = user_book_states.times_opened + 1,
           updated_at = excluded.updated_at,
           rev = excluded.rev`,
        [
          auth.user.id,
          bookId,
          status,
          body.percent ?? null,
          JSON.stringify({ kind: body.targetKind, value: body.targetValue }),
          body.fileId ?? null,
          now,
          now,
          auth.device.id,
          now,
          now,
          rev,
        ],
      );
      db.run(
        'INSERT INTO position_history (user_id, book_id, file_id, device_id, target_kind, target_value, percent, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
        [auth.user.id, bookId, body.fileId ?? null, auth.device.id, body.targetKind, body.targetValue, body.percent ?? null, now],
      );
    });

    return {
      rev,
      state: db.get('SELECT * FROM user_book_states WHERE user_id = ? AND book_id = ?', [auth.user.id, bookId]),
    };
  });

  app.get('/api/v1/progress', authed, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const query = parseWith(
      z.object({
        status: z.enum(['none', 'reading', 'finished', 'abandoned', 'on_hold']).optional(),
        limit: z.coerce.number().int().min(1).max(200).default(50),
      }),
      request.query,
      'query',
    );
    const rows = db.all(
      `SELECT s.book_id, s.status, s.progress_percent, s.position_json, s.last_read_at, s.rev,
              b.title, b.has_cover, b.content_updated_at
         FROM user_book_states s JOIN books b ON b.id = s.book_id
        WHERE s.user_id = ? AND b.deleted_at IS NULL AND s.deleted_at IS NULL
          ${query.status ? 'AND s.status = ?' : ''}
        ORDER BY s.last_read_at DESC NULLS LAST, s.book_id
        LIMIT ?`,
      query.status ? [auth.user.id, query.status, query.limit] : [auth.user.id, query.limit],
    );
    return { items: rows };
  });

  // ------------------------------------------------------------------ bookmarks

  app.get('/api/v1/bookmarks', authed, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const query = parseWith(
      z.object({ bookId: idSchema.optional(), since: z.coerce.number().int().min(0).optional() }),
      request.query,
      'query',
    );
    const conditions = ['user_id = ?'];
    const params: (string | number)[] = [auth.user.id];
    if (query.bookId) {
      conditions.push('book_id = ?');
      params.push(query.bookId);
    }
    if (query.since !== undefined) {
      conditions.push('rev > ?');
      params.push(query.since);
    }
    // Tombstones are returned, not filtered: an offline client that never learns about a delete
    // keeps showing the bookmark forever.
    return {
      items: db.all(
        `SELECT id, book_id, file_id, target_kind, target_value, title, excerpt, note, color, rev, deleted_at, updated_at
           FROM bookmarks WHERE ${conditions.join(' AND ')} ORDER BY rev`,
        params,
      ),
    };
  });

  app.post('/api/v1/bookmarks', authed, async (request, reply) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const body = parseWith(BookmarkBody, request.body, 'body');
    const now = new Date().toISOString();
    const id = body.id ?? newId();
    const rev = nextBookmarkRev(auth.user.id);

    const existing = db.get<{ id: string }>('SELECT id FROM bookmarks WHERE id = ?', [id]);
    if (existing) {
      db.run(
        `UPDATE bookmarks SET target_kind = ?, target_value = ?, title = ?, excerpt = ?, note = ?, color = ?,
                              updated_at = ?, rev = ?, deleted_at = NULL
         WHERE id = ? AND user_id = ?`,
        [
          body.targetKind,
          body.targetValue,
          body.title ?? null,
          body.excerpt ?? null,
          body.note ?? null,
          body.color ?? null,
          now,
          rev,
          id,
          auth.user.id,
        ],
      );
    } else {
      db.run(
        `INSERT INTO bookmarks (id, user_id, book_id, file_id, target_kind, target_value, title, excerpt, note, color, created_at, updated_at, rev)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          id,
          auth.user.id,
          body.bookId,
          body.fileId ?? null,
          body.targetKind,
          body.targetValue,
          body.title ?? null,
          body.excerpt ?? null,
          body.note ?? null,
          body.color ?? null,
          now,
          now,
          rev,
        ],
      );
    }
    return reply.status(201).send({ bookmark: db.get('SELECT * FROM bookmarks WHERE id = ?', [id]) });
  });

  app.delete('/api/v1/bookmarks/:id', authed, async (request, reply) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    const rev = nextBookmarkRev(auth.user.id);
    const changed = db.run(
      'UPDATE bookmarks SET deleted_at = ?, updated_at = ?, rev = ? WHERE id = ? AND user_id = ?',
      [new Date().toISOString(), new Date().toISOString(), rev, id, auth.user.id],
    ).changes;
    if (changed === 0) throw new AppError('not_found', 'Bookmark not found');
    return reply.status(204).send();
  });

  // ------------------------------------------------------------------ sync

  /**
   * Delta pull. `cursor` is the caller's last seen rev; everything at or below it is already known.
   * Deletions travel as tombstones so a device that was offline for a month converges instead of
   * resurrecting rows the server has removed.
   */
  app.get('/api/v1/sync/delta', authed, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const query = parseWith(
      z.object({
        cursor: z.coerce.number().int().min(0).default(0),
        limit: z.coerce.number().int().min(1).max(1000).default(500),
      }),
      request.query,
      'query',
    );

    const states = db.all(
      `SELECT 'state' AS kind, book_id, status, progress_percent, position_json, position_file_id,
              last_read_at, rev, deleted_at
         FROM user_book_states WHERE user_id = ? AND rev > ? ORDER BY rev LIMIT ?`,
      [auth.user.id, query.cursor, query.limit],
    );
    const marks = db.all(
      `SELECT 'bookmark' AS kind, id, book_id, target_kind, target_value, title, excerpt, note, color,
              rev, deleted_at
         FROM bookmarks WHERE user_id = ? AND rev > ? ORDER BY rev LIMIT ?`,
      [auth.user.id, query.cursor, query.limit],
    );
    const items = [...states, ...marks].sort(
      (a, b) => Number((a as { rev: number }).rev) - Number((b as { rev: number }).rev),
    );
    const nextCursor = items.length > 0 ? Number((items[items.length - 1] as { rev: number }).rev) : query.cursor;

    return {
      items,
      nextCursor,
      serverRev: nextCursor,
      hasMore: items.length === query.limit,
    };
  });

  /**
   * Push from an offline client. Each change carries the rev it was based on; the server rejects a
   * stale base rather than silently applying it, so the client can decide — for reading position
   * the honest resolution is usually "keep whichever is further along", which only the client can
   * judge because only it knows what the user was looking at.
   */
  app.post('/api/v1/sync/push', authed, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const body = parseWith(
      z.object({
        changes: z
          .array(
            z.object({
              kind: z.enum(['state', 'bookmark']),
              id: idSchema.optional(),
              bookId: idSchema.optional(),
              baseRev: z.number().int().min(0).optional(),
              payload: z.record(z.string(), z.unknown()),
            }),
          )
          .min(1)
          .max(500),
      }),
      request.body,
      'body',
    );

    const applied: { index: number; kind: string; id: string; rev: number }[] = [];
    const conflicts: { index: number; kind: string; id: string; serverRev: number }[] = [];

    db.tx(() => {
      body.changes.forEach((change, index) => {
        if (change.kind === 'state') {
          if (!change.bookId) {
            conflicts.push({ index, kind: 'state', id: '', serverRev: 0 });
            return;
          }
          const current = db.get<{ rev: number; deleted_at: string | null }>(
            'SELECT rev, deleted_at FROM user_book_states WHERE user_id = ? AND book_id = ?',
            [auth.user.id, change.bookId],
          );
          if (current && change.baseRev !== undefined && change.baseRev < current.rev) {
            conflicts.push({ index, kind: 'state', id: change.bookId, serverRev: current.rev });
            return;
          }
          const rev = nextRev(auth.user.id);
          const now = new Date().toISOString();
          const payload = change.payload as { status?: string; percent?: number | null; position?: unknown };
          db.run(
            `INSERT INTO user_book_states (user_id, book_id, status, progress_percent, position_json, created_at, updated_at, rev)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT (user_id, book_id) DO UPDATE SET
               status = coalesce(excluded.status, user_book_states.status),
               progress_percent = coalesce(excluded.progress_percent, user_book_states.progress_percent),
               position_json = coalesce(excluded.position_json, user_book_states.position_json),
               updated_at = excluded.updated_at,
               rev = excluded.rev`,
            [
              auth.user.id,
              change.bookId,
              payload.status ?? 'reading',
              payload.percent ?? null,
              payload.position === undefined ? null : JSON.stringify(payload.position),
              now,
              now,
              rev,
            ],
          );
          applied.push({ index, kind: 'state', id: change.bookId, rev });
          return;
        }

        const id = change.id ?? newId();
        const current = db.get<{ rev: number }>('SELECT rev FROM bookmarks WHERE id = ? AND user_id = ?', [
          id,
          auth.user.id,
        ]);
        if (current && change.baseRev !== undefined && change.baseRev < current.rev) {
          conflicts.push({ index, kind: 'bookmark', id, serverRev: current.rev });
          return;
        }
        const rev = nextBookmarkRev(auth.user.id);
        const now = new Date().toISOString();
        const payload = change.payload as {
          bookId?: string;
          targetKind?: string;
          targetValue?: string;
          title?: string | null;
          note?: string | null;
          deleted?: boolean;
        };
        db.run(
          `INSERT INTO bookmarks (id, user_id, book_id, target_kind, target_value, title, note, created_at, updated_at, rev, deleted_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT (id) DO UPDATE SET
             title = coalesce(excluded.title, bookmarks.title),
             note = coalesce(excluded.note, bookmarks.note),
             updated_at = excluded.updated_at,
             rev = excluded.rev,
             deleted_at = excluded.deleted_at`,
          [
            id,
            auth.user.id,
            payload.bookId ?? change.bookId ?? '',
            payload.targetKind ?? 'char',
            payload.targetValue ?? '0',
            payload.title ?? null,
            payload.note ?? null,
            now,
            now,
            rev,
            payload.deleted ? now : null,
          ],
        );
        applied.push({ index, kind: 'bookmark', id, rev });
      });
    });

    return { applied, conflicts, serverRev: Math.max(0, ...applied.map((a) => a.rev)) };
  });

  /**
   * Everything a fresh device needs to become useful offline in one request: the shelf, the covers
   * and one download URL per file. Sending it as one document avoids the N+1 that would otherwise
   * be a request per book on first launch.
   */
  app.get('/api/v1/sync/offline-manifest', authed, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const books = db.all<{ id: string; title: string; content_updated_at: string; has_cover: number; file_count: number }>(
      `SELECT b.id, b.title, b.content_updated_at, b.has_cover, b.file_count
         FROM books b
        WHERE b.deleted_at IS NULL
        ORDER BY b.added_at DESC
        LIMIT 5000`,
    );
    const files = db.all<{ id: string; book_id: string; format: string; size_bytes: number; sha256: string; is_primary: number }>(
      `SELECT bf.id, bf.book_id, bf.format, bf.size_bytes, bf.sha256, bf.is_primary
         FROM book_files bf JOIN books b ON b.id = bf.book_id
        WHERE b.deleted_at IS NULL
        ORDER BY bf.book_id, bf.is_primary DESC`,
    );
    return {
      serverTime: new Date().toISOString(),
      books: books.map((b) => ({
        ...b,
        coverUrl: b.has_cover === 1 ? `/api/v1/covers/${b.id}` : null,
        files: files.filter((f) => f.book_id === b.id),
      })),
    };
  });
}
