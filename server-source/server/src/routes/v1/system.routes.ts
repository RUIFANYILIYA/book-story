import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parseWith } from '../../http/validate.ts';
import { createGuards } from '../../auth/guard.ts';
import type { Db } from '../../db/index.ts';
import type { SessionService } from '../../auth/sessions.ts';

/**
 * Operational endpoints.
 *
 * `/stats` is the one an operator opens when the disk is filling up: it reports the shape of what
 * is stored rather than a single byte count, because "932 GB" is not actionable and "932 GB, of
 * which 89 GB is one duplicated PDF format" is.
 *
 * Deleted rows are counted separately rather than excluded: a tombstone still occupies a row and
 * its blob may still be on disk within the retention window, so a total that hides them would
 * disagree with `du` on the volume.
 */
export function registerSystemRoutes(app: FastifyInstance, deps: { db: Db; session: SessionService }): void {
  const { db } = deps;
  const guards = createGuards(deps.session);

  app.get(
    '/api/v1/stats',
    { preHandler: [guards.authenticate, guards.require('library:read')] },
    async () => {
      const totals = db.get<{
        books: number;
        deleted_books: number;
        files: number;
        bytes: number;
        needs_review: number;
        without_cover: number;
      }>(
        `SELECT
           (SELECT count(*) FROM books WHERE deleted_at IS NULL) AS books,
           (SELECT count(*) FROM books WHERE deleted_at IS NOT NULL) AS deleted_books,
           (SELECT count(*) FROM book_files) AS files,
           (SELECT COALESCE(SUM(size_bytes), 0) FROM blobs WHERE deleted_at IS NULL) AS bytes,
           (SELECT count(*) FROM books WHERE needs_review = 1 AND deleted_at IS NULL) AS needs_review,
           (SELECT count(*) FROM books WHERE has_cover = 0 AND deleted_at IS NULL) AS without_cover`,
      );

      return {
        books: totals?.books ?? 0,
        deletedBooks: totals?.deleted_books ?? 0,
        files: totals?.files ?? 0,
        bytes: totals?.bytes ?? 0,
        needsReview: totals?.needs_review ?? 0,
        withoutCover: totals?.without_cover ?? 0,
        byFormat: db.all(
          `SELECT bf.format AS format, count(*) AS files, COALESCE(SUM(bf.size_bytes), 0) AS bytes
             FROM book_files bf JOIN books b ON b.id = bf.book_id
            WHERE b.deleted_at IS NULL GROUP BY bf.format ORDER BY bytes DESC`,
        ),
        jobs: db.all('SELECT state, count(*) AS n FROM ingest_jobs GROUP BY state'),
        users: db.get<{ n: number }>('SELECT count(*) AS n FROM users')?.n ?? 0,
        sessions:
          db.get<{ n: number }>(
            "SELECT count(*) AS n FROM auth_devices WHERE revoked_at IS NULL AND expires_at > ?",
            [new Date().toISOString()],
          )?.n ?? 0,
        collections: db.get<{ n: number }>('SELECT count(*) AS n FROM collections WHERE deleted_at IS NULL')?.n ?? 0,
        categories: db.get<{ n: number }>('SELECT count(*) AS n FROM categories')?.n ?? 0,
        tags: db.get<{ n: number }>('SELECT count(*) AS n FROM tags')?.n ?? 0,
      };
    },
  );

  /**
   * Audit trail. Admin-only, and deliberately not filtered by "my own actions": the reason to read
   * this is to find out what somebody else did.
   */
  app.get(
    '/api/v1/audit-logs',
    { preHandler: [guards.authenticate, guards.require('audit:read')] },
    async (request) => {
      const query = parseWith(
        z.object({
          action: z.string().trim().max(100).optional(),
          targetType: z.string().trim().max(100).optional(),
          limit: z.coerce.number().int().min(1).max(500).default(50),
        }),
        request.query,
        'query',
      );
      const conditions: string[] = [];
      const params: (string | number)[] = [];
      if (query.action) {
        conditions.push('action = ?');
        params.push(query.action);
      }
      if (query.targetType) {
        conditions.push('target_type = ?');
        params.push(query.targetType);
      }
      const where = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';
      return {
        items: db.all(
          `SELECT at, actor_user_id, action, target_type, target_id, book_id, result, ip, request_id
             FROM operation_logs ${where} ORDER BY at DESC LIMIT ?`,
          [...params, query.limit],
        ),
      };
    },
  );
}
