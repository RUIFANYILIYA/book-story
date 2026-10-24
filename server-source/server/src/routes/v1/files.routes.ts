import type { FastifyInstance } from 'fastify';
import { existsSync } from 'node:fs';
import { z } from 'zod';
import { parseWith, idSchema } from '../../http/validate.ts';
import { AppError } from '../../errors.ts';
import { createGuards } from '../../auth/guard.ts';
import { BlobRepo } from '../../db/repositories/blob.repo.ts';
import { detectImageType } from '../../library/covers.ts';
import { extractEpubChapters } from '../../library/epub-text.ts';
import { ZipError } from '../../metadata/zip.ts';
import { sendFileWithRanges } from '../../http/range-stream.ts';
import type { Config } from '../../config.ts';
import type { Db } from '../../db/index.ts';
import type { SessionService } from '../../auth/sessions.ts';

/** The most a reader may hand us for a cover. Books get the global limit; pictures do not need it. */
const MAX_COVER_BYTES = 12 * 1024 * 1024;

/**
 * Serving the bytes.
 *
 * A file's `id` is immutable: replacing a book's file inserts a new row rather than pointing the
 * old one at new content. That is what makes the id usable as a client cache key — a device that
 * downloaded file X yesterday and resumes it today gets the same bytes, even if the library owner
 * swapped the book's file in between. The old row stays readable for the retention window for
 * exactly that reason.
 */
export function registerFileRoutes(
  app: FastifyInstance,
  deps: { db: Db; config: Config; session: SessionService },
): void {
  const { db, config } = deps;
  const blobs = new BlobRepo(db, config.libraryDir);
  const guards = createGuards(deps.session);
  const pre = { preHandler: [guards.authenticate, guards.require('library:read')] };
  // The private layer: any signed-in reader may re-cover their own view of a shared book, which is
  // the same rule `POST /collections` follows (create your own grouping without being a curator).
  const authed = { preHandler: [guards.authenticate] };

  const fileRow = (fileId: string) =>
    db.get<{
      id: string;
      book_id: string;
      blob_id: string;
      filename: string;
      media_type: string;
      sha256: string;
      size_bytes: number;
      format: string;
    }>(
      `SELECT id, book_id, blob_id, filename, media_type, sha256, size_bytes, format
         FROM book_files WHERE id = ? AND status = 'ready'`,
      [fileId],
    );

  const serve = async (
    request: Parameters<typeof sendFileWithRanges>[0],
    reply: Parameters<typeof sendFileWithRanges>[1],
    disposition: 'attachment' | 'inline',
  ) => {
    const { fileId } = parseWith(z.object({ fileId: idSchema }), request.params, 'params');
    const file = fileRow(fileId);
    if (!file) throw new AppError('file_not_found', 'File not found');
    const blob = blobs.byId(file.blob_id);
    // A blob can be missing from disk while its row survives (a restore that skipped the library
    // volume). Saying so beats a 500 from a failed open.
    if (!blob) throw new AppError('storage_unavailable', 'The stored file is missing', { details: { fileId } });
    // A linked book's bytes are the user's, outside the library — they may have moved or deleted
    // the file since it was catalogued, which is a different failure from a missing blob.
    if (blob.external === 1 && !existsSync(blobs.absolutePath(blob))) {
      throw new AppError('storage_unavailable', 'The linked file is no longer at its recorded path', {
        details: { fileId, path: blobs.absolutePath(blob) },
      });
    }

    await sendFileWithRanges(request, reply, {
      path: blobs.absolutePath(blob),
      filename: file.filename,
      sha256: file.sha256,
      mediaType: file.media_type,
      disposition,
      extraHeaders: { 'X-File-Id': file.id, 'X-Book-Id': file.book_id },
    });
  };

  app.route({
    method: ['GET', 'HEAD'],
    url: '/api/v1/files/:fileId/download',
    preHandler: pre.preHandler,
    handler: (request, reply) => serve(request, reply, 'attachment'),
  });

  app.route({
    method: ['GET', 'HEAD'],
    url: '/api/v1/files/:fileId/stream',
    // Inline, so a reader or a WebView can treat the response as the document rather than as a
    // download it has to save first.
    preHandler: pre.preHandler,
    handler: (request, reply) => serve(request, reply, 'inline'),
  });

  /**
   * A book's EPUB, cut into chapters for the console's reader.
   *
   * The spine is what defines "a chapter" in an EPUB — the file has no chapter syntax the way a TXT
   * does — so the split happens here rather than in the browser, where unzipping a 6 MB container
   * would mean shipping a zip reader to the page. Extraction runs per request (a few hundred
   * milliseconds for a novel): caching it would hold megabytes of HTML per open book and would need
   * invalidating whenever the file is replaced.
   */
  app.get('/api/v1/books/:bookId/epub', pre, async (request) => {
    const { bookId } = parseWith(z.object({ bookId: idSchema }), request.params, 'params');
    const book = db.get<{ id: string }>('SELECT id FROM books WHERE id = ? AND deleted_at IS NULL', [bookId]);
    if (!book) throw new AppError('book_not_found', 'Book not found');

    const file = db.get<{ id: string; blob_id: string }>(
      `SELECT id, blob_id FROM book_files
        WHERE book_id = ? AND format = 'epub' AND status = 'ready'
        ORDER BY is_primary DESC, created_at
        LIMIT 1`,
      [bookId],
    );
    if (!file) throw new AppError('not_found', 'This book has no readable EPUB file');
    const blob = blobs.byId(file.blob_id);
    if (!blob) {
      throw new AppError('storage_unavailable', 'The stored file is missing', { details: { bookId } });
    }
    if (blob.external === 1 && !existsSync(blobs.absolutePath(blob))) {
      throw new AppError('storage_unavailable', 'The linked file is no longer at its recorded path', {
        details: { bookId, path: blobs.absolutePath(blob) },
      });
    }

    try {
      const content = await extractEpubChapters(blobs.absolutePath(blob));
      return { bookId, fileId: file.id, ...content };
    } catch (error) {
      if (error instanceof ZipError) {
        // The usual cause is a DRM-wrapped EPUB: the spine is there, the text is encrypted, and no
        // amount of parsing produces a chapter. Saying so beats a 500 with a stack trace.
        throw new AppError('unprocessable', '这本书的 EPUB 读不出章节，可能已加密或文件损坏', {
          details: { bookId, reason: error.reason },
        });
      }
      throw error;
    }
  });

  /**
   * Covers, with the personal override taking precedence over the shared one.
   *
   * The override lives in `user_book_states`, so the same book gets a different image depending on
   * who asks — which is the whole point of a per-reader cover, and also why this cannot be cached
   * as a public resource.
   */
  app.get('/api/v1/covers/:bookId', { preHandler: [guards.authenticate] }, async (request, reply) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { bookId } = parseWith(z.object({ bookId: idSchema }), request.params, 'params');

    const book = db.get<{ id: string; cover_blob_id: string | null; cover_updated_at: string | null }>(
      'SELECT id, cover_blob_id, cover_updated_at FROM books WHERE id = ? AND deleted_at IS NULL',
      [bookId],
    );
    if (!book) throw new AppError('book_not_found', 'Book not found');

    const override = db.get<{ cover_override_blob_id: string | null; cover_override_updated_at: string | null }>(
      'SELECT cover_override_blob_id, cover_override_updated_at FROM user_book_states WHERE user_id = ? AND book_id = ?',
      [auth.user.id, bookId],
    );
    const blobId = override?.cover_override_blob_id ?? book.cover_blob_id;
    if (!blobId) throw new AppError('not_found', 'This book has no cover');

    const blob = blobs.byId(blobId);
    if (!blob) throw new AppError('storage_unavailable', 'The stored cover is missing');

    const updatedAt = override?.cover_override_blob_id ? override.cover_override_updated_at : book.cover_updated_at;
    const etag = `"${blob.sha256.slice(0, 32)}"`;
    reply.header('ETag', etag);
    reply.header('Content-Type', blob.media_type);
    reply.header('Content-Length', String(blob.size_bytes));
    // `private` because a per-user override must not be shared by an intermediary with another user.
    reply.header('Cache-Control', 'private, max-age=86400');
    if (updatedAt) reply.header('X-Cover-Updated-At', updatedAt);
    if (request.headers['if-none-match'] === etag) {
      reply.code(304);
      return reply.send();
    }

    const { createReadStream } = await import('node:fs');
    reply.code(200);
    return reply.send(createReadStream(blobs.absolutePath(blob)));
  });

  /**
   * The reader's own cover for a book, written.
   *
   * A cover is a picture, not a book, so the global upload limit (which exists so a 512 MB text
   * file can arrive) is the wrong ceiling here: it is the difference between a rejected request and
   * a library directory filled with someone's 400 MB screenshot. The cap is enforced while reading
   * the part, not after, so an oversized body costs the configured maximum rather than whatever the
   * client decided to send.
   *
   * What is *not* validated is the picture's content beyond its magic bytes — no decoding, no
   * dimension check, no re-encoding — which is the same rule ingest applies to embedded covers. A
   * server that resampled covers would need a graphics library for the whole product's sake, and the
   * plan's answer to a bad cover has always been "the reader replaces it".
   */
  app.post('/api/v1/books/:bookId/cover', authed, async (request, reply) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { bookId } = parseWith(z.object({ bookId: idSchema }), request.params, 'params');
    if (!request.isMultipart()) {
      throw new AppError('unsupported_media_type', 'Send the image as multipart/form-data');
    }
    requireBook(db, bookId);

    // `throwFileSizeLimit: false` so the cap is reported by the code below rather than by the
    // plugin's own error: this route knows the number, and a message that names it is a message the
    // reader can act on.
    const part = await request.file({
      throwFileSizeLimit: false,
      limits: { fileSize: MAX_COVER_BYTES, files: 1, fields: 8, fieldSize: 4096 },
    });
    if (!part) throw new AppError('bad_request', 'No image in the request; expected a file field named "cover"');
    const bytes = await part.toBuffer();
    // `truncated` is how the plugin reports the size limit it applied while reading.
    if (part.file.truncated) {
      throw new AppError('payload_too_large', `A cover must be at most ${MAX_COVER_BYTES} bytes`);
    }
    const detected = detectImageType(new Uint8Array(bytes));
    if (!detected) {
      throw new AppError('unsupported_media_type', 'Not an image we can name (jpeg, png, gif, webp, bmp)');
    }

    const blob = await blobs.putBuffer(bytes, detected.mediaType, 'cover');
    const now = new Date().toISOString();
    const replaced = db.tx(() => {
      const existing = db.get<{ cover_override_blob_id: string | null }>(
        'SELECT cover_override_blob_id FROM user_book_states WHERE user_id = ? AND book_id = ?',
        [auth.user.id, bookId],
      );
      db.run(
        `INSERT INTO user_book_states
           (user_id, book_id, shelf, status, created_at, updated_at, rev, cover_override_blob_id, cover_override_updated_at)
         VALUES (?, ?, 'library', 'none', ?, ?, ?, ?, ?)
         ON CONFLICT (user_id, book_id) DO UPDATE SET
           cover_override_blob_id = excluded.cover_override_blob_id,
           cover_override_updated_at = excluded.cover_override_updated_at,
           updated_at = excluded.updated_at,
           rev = excluded.rev`,
        [auth.user.id, bookId, now, now, nextRev(db, auth.user.id), blob.id, now],
      );
      return existing?.cover_override_blob_id ?? null;
    });
    // The new reference is already counted; the one this replaced is released. Identical bytes
    // dedup to the same blob, so re-uploading a cover must not drop its own refcount to zero.
    if (replaced && replaced !== blob.id) blobs.decrement(replaced);

    reply.code(201);
    return {
      bookId,
      coverUrl: `/api/v1/covers/${bookId}`,
      mediaType: detected.mediaType,
      sizeBytes: blob.size_bytes,
      sha256: blob.sha256,
      updatedAt: now,
    };
  });

  /**
   * Back to the library's own cover.
   *
   * 204 whether or not an override was set: from the reader's side both cases look the same, and a
   * "you had nothing to clear" error would turn a menu item into a state machine.
   */
  app.delete('/api/v1/books/:bookId/cover', authed, async (request, reply) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { bookId } = parseWith(z.object({ bookId: idSchema }), request.params, 'params');
    requireBook(db, bookId);

    const existing = db.get<{ cover_override_blob_id: string | null }>(
      'SELECT cover_override_blob_id FROM user_book_states WHERE user_id = ? AND book_id = ?',
      [auth.user.id, bookId],
    );
    if (existing?.cover_override_blob_id) {
      const now = new Date().toISOString();
      db.tx(() => {
        db.run(
          `UPDATE user_book_states
              SET cover_override_blob_id = NULL, cover_override_updated_at = NULL, updated_at = ?, rev = ?
            WHERE user_id = ? AND book_id = ?`,
          [now, nextRev(db, auth.user.id), auth.user.id, bookId],
        );
      });
      blobs.decrement(existing.cover_override_blob_id);
    }
    return reply.status(204).send();
  });
}

/** A cover belongs to a book that exists; saying so beats an opaque foreign-key failure. */
function requireBook(db: Db, bookId: string): void {
  const book = db.get<{ id: string }>('SELECT id FROM books WHERE id = ? AND deleted_at IS NULL', [bookId]);
  if (!book) throw new AppError('book_not_found', 'Book not found');
}

/**
 * The per-user delta cursor, moved forward one.
 *
 * A cover change is a change to the reader's own row, so it takes a revision like any other — that
 * is how a second device learns to re-fetch the cover. It is also safe for the progress merge, which
 * reads the row's *existing* percentage: the delta carries no cover columns, so a cover-only
 * revision arrives as "the same position, written again".
 */
function nextRev(db: Db, userId: string): number {
  return (db.get<{ n: number | null }>('SELECT max(rev) AS n FROM user_book_states WHERE user_id = ?', [userId])?.n ?? 0) + 1;
}
