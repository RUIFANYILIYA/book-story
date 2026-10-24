import { createHash } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { mkdir, rm, stat } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';
import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import type { Config } from '../../config.ts';
import type { Db } from '../../db/index.ts';
import { AppError } from '../../errors.ts';
import { newId } from '../../ids.ts';
import { parseWith } from '../../http/validate.ts';
import { createGuards } from '../../auth/guard.ts';
import { EBOOK_EXTENSIONS, ingestFile, numberedFilename } from '../../library/ingest.ts';
import { BlobRepo } from '../../db/repositories/blob.repo.ts';
import { BookRepo } from '../../db/repositories/book.repo.ts';
import type { SessionService } from '../../auth/sessions.ts';

/**
 * Multipart ingest.
 *
 * The file is streamed straight to a temp path with the hash computed on the way through, never
 * buffered: the upload limit is hundreds of megabytes by default and a browser can open several
 * uploads at once, which an in-memory handler would not survive.
 *
 * Two behaviours of @fastify/multipart are load-bearing and easy to get wrong. Text fields arrive
 * *before* the file part, so `request.body` is not usable and the parts must be walked in order.
 * And when `limits.fileSize` trips, the stream errors mid-write while the partial temp file is
 * still on disk — if that file is not unlinked here the tmp directory fills the volume over time.
 */

const ModeSchema = z.enum(['auto', 'create', 'attach', 'replace']);
const IdSchema = z.string().regex(/^[0-9A-HJKMNP-TV-Z]{26}$/u, 'must be a 26-character id');
/**
 * How to settle a file whose name is already taken. Only ever answered by a human: the first
 * request carries no decision and is answered with a 409 that describes the clash, and the retry
 * carries one of these. `replace` and `rename` both land on existing ingest modes — the decision
 * is *which* book the bytes end up in, not a new storage behaviour.
 */
const ConflictSchema = z.enum(['replace', 'skip', 'rename']);

interface ReceivedUpload {
  uploadId: string;
  tmpPath: string;
  filename: string;
  /** The name as it arrived, kept for the receipt after `rename` rewrites `filename`. */
  originalFilename: string;
  sizeBytes: number;
  sha256: string;
  mode: 'auto' | 'create' | 'attach' | 'replace';
  targetBookId: string | null;
  charset: string | null;
  conflict: 'replace' | 'skip' | 'rename' | null;
}

export function registerUploadRoutes(
  app: FastifyInstance,
  deps: { db: Db; config: Config; session: SessionService },
): void {
  const { db, config } = deps;
  const guards = createGuards(deps.session);
  const pre = { preHandler: [guards.authenticate, guards.require('file:upload')] };

  app.post('/api/v1/uploads', pre, async (request: FastifyRequest, reply: FastifyReply) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');

    const received = await receiveMultipart(request, config);
    try {
      const books = new BookRepo(db);
      const blobs = new BlobRepo(db, config.libraryDir);

      // The clash is looked for on every attempt, decision or not: the retry that carries
      // `conflict` has to find the same row the 409 described, and it arrives with no other
      // identifier than the filename. Only the *question* is skipped when a decision is present.
      // An explicit `mode` or `bookId` opts out entirely — a client that named its target book
      // has said where the bytes go — and identical bytes keep their own, more precise answer
      // (`duplicate_content`) rather than being re-asked as a name question.
      let clash = null;
      if (received.mode === 'auto' && received.targetBookId === null && !blobs.existing(received.sha256)) {
        clash = books.findFilesByName(received.filename)[0] ?? null;
      }
      if (clash && !received.conflict) {
        throw new AppError(
          'filename_conflict',
          `书库中已有同名文件「${clash.filename}」（《${clash.book_title}》）`,
          {
            details: {
              filename: clash.filename,
              bookId: clash.book_id,
              bookTitle: clash.book_title,
              fileId: clash.id,
              sizeBytes: clash.size_bytes,
              format: clash.format,
            },
          },
        );
      }

      // Nothing to store and nothing to change: the decision was "leave the shelf alone".
      if (received.conflict === 'skip') {
        return reply.status(200).send({
          skipped: true,
          filename: received.originalFilename,
          uploadId: received.uploadId,
        });
      }

      let mode = received.mode;
      let targetBookId = received.targetBookId;
      let filename = received.filename;
      /** The rows the incoming file supersedes; captured before the ingest that replaces them. */
      let superseded: string[] = [];

      if (received.conflict === 'replace' && clash) {
        // The new bytes join the book that held the old file, as its primary, so the shelf, the
        // reading progress and the id all survive an update. The superseded rows are dropped
        // after a successful ingest, never before: a parse that fails must not cost the old file.
        targetBookId = clash.book_id;
        mode = 'replace';
        superseded = books.findFilesByName(filename).map((f) => f.id);
      }

      if (received.conflict === 'rename') {
        // Keep both: the incoming file joins the book that already holds the name, as the new
        // primary, under a numbered name — the old file row stays, demoted but downloadable.
        //
        // A second *book* is deliberately not created. The dedupe index on
        // (title_norm, sort_key) refuses it outright, and a shelf entry called "三体 (1)" is not
        // what "rename the file" asked for: what was asked for is a second file, and that is
        // what this makes. Reading progress, shelf and id all stay with the one book.
        mode = 'replace';
        targetBookId = clash?.book_id ?? targetBookId;
        let n = 1;
        while (books.findFilesByName(filename).length > 0) {
          filename = numberedFilename(received.filename, n++);
        }
      }

      const result = await ingestFile({ db, config }, {
        sourcePath: received.tmpPath,
        filename,
        userId: auth.user.id,
        mode,
        targetBookId,
        charset: received.charset,
      });

      if (superseded.length > 0) books.deleteFiles(superseded);

      db.run(
        `INSERT INTO upload_sessions (id, user_id, filename, declared_size, received_size, sha256,
                                      tmp_path, mode, target_book_id, status, created_at, updated_at, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'committed', ?, ?, ?)`,
        [
          received.uploadId,
          auth.user.id,
          received.originalFilename,
          null,
          received.sizeBytes,
          received.sha256,
          received.tmpPath,
          mode,
          targetBookId,
          new Date().toISOString(),
          new Date().toISOString(),
          new Date(Date.now() + 86_400_000).toISOString(),
        ],
      );

      return reply.status(201).send({
        uploadId: received.uploadId,
        bookId: result.bookId,
        fileId: result.fileId,
        blobId: result.blobId,
        format: result.format,
        title: result.title,
        created: result.created,
        deduped: result.deduped,
        matchedBy: result.matchedBy,
        conflict: received.conflict,
        storedFilename: filename,
        book: { needsReview: result.needsReview, warnings: result.warnings },
      });
    } catch (err) {
      // ingestFile may have failed before it moved the temp file into the content-addressed
      // store, and a retry of the same bytes must not find a stale copy waiting there.
      throw err;
    } finally {
      // Always, not only on failure: on success ingestFile renames the file away and leaves this
      // directory behind, so a catch-only cleanup leaks one directory per upload forever.
      await rm(dirname(received.tmpPath), { recursive: true, force: true }).catch(() => undefined);
    }
  });

  /**
   * Bulk import from a directory the server can already see.
   *
   * This is the path that matters for a NAS: the library folder is usually already on the share,
   * and posting ten thousand files over HTTP to fill it in is absurd. By default files are *moved*
   * into the content-addressed store rather than copied, so a completed import leaves no duplicate
   * behind and re-running the import is a no-op instead of a second copy of everything.
   *
   * `link: true` keeps them where they are instead: the library records the path, the bytes stay
   * the user's, and nothing is written to the source folder at all. That is what a read-only mount
   * needs, and what makes cataloguing a large existing collection cost no disk space.
   */
  app.post('/api/v1/import/dir', { preHandler: [guards.authenticate, guards.require('library:edit')] }, async (request) => {
    const body = parseWith(
      z.object({
        path: z.string().min(1).max(1000),
        dryRun: z.boolean().default(false),
        limit: z.number().int().min(1).max(5000).default(500),
        link: z.boolean().default(false),
      }),
      request.body,
      'body',
    );

    const root = resolveWithin(config.importDir ?? config.libraryDir, body.path);
    const files = await collectEbookFiles(root, body.limit);
    if (body.dryRun) {
      return { scanned: files.length, dryRun: true, bytes: 0, imported: [], wouldImport: files.map((f) => f.name) };
    }

    const imported: { path: string; bookId: string; format: string; title: string }[] = [];
    const failed: { path: string; error: string }[] = [];
    let bytes = 0;

    for (const file of files) {
      try {
        const result = await ingestFile({ db, config }, {
          sourcePath: file.path,
          filename: file.name,
          userId: request.auth?.user.id ?? null,
          mode: 'auto',
          link: body.link,
        });
        bytes += await sizeOf(file.path).catch(() => 0);
        imported.push({ path: file.path, bookId: result.bookId, format: result.format, title: result.title });
      } catch (err) {
        // Duplicate content is the expected answer for a re-scan, not a failure worth reporting.
        if (err instanceof AppError && err.code === 'duplicate_content') continue;
        failed.push({ path: file.path, error: err instanceof Error ? err.message : String(err) });
      }
    }

    return { scanned: files.length, imported: imported.length, bytes, results: imported, failed };
  });

  app.get('/api/v1/uploads/:id', pre, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { id } = parseWith(z.object({ id: IdSchema }), request.params, 'params');
    const row = db.get<Record<string, unknown>>(
      'SELECT id, filename, received_size, sha256, mode, status, created_at, updated_at FROM upload_sessions WHERE id = ? AND user_id = ?',
      [id, auth.user.id],
    );
    if (!row) throw new AppError('not_found', 'Upload not found');
    return row;
  });
}

async function receiveMultipart(
  request: FastifyRequest,
  config: Config,
): Promise<ReceivedUpload> {
  if (!request.isMultipart()) {
    throw new AppError('unsupported_media_type', 'Upload must be multipart/form-data');
  }

  const uploadId = newId();
  const tmpDir = join(config.paths.uploadsTmp, uploadId);
  await mkdir(tmpDir, { recursive: true });

  const fields: Record<string, string> = {};
  let filePart: { filename: string; path: string; sha256: string; size: number } | null = null;

  // parts() rather than files(): text fields must be captured, and they arrive before the file.
  const parts = request.parts();
  for await (const part of parts) {
    if (part.type === 'field') {
      if (typeof part.value === 'string' || typeof part.value === 'number' || typeof part.value === 'boolean') {
        fields[part.fieldname] = String(part.value);
      }
      continue;
    }
    if (filePart) {
      await part.file.resume();
      throw new AppError('bad_request', 'Only one file per upload; send them one at a time');
    }
    const target = join(tmpDir, 'incoming');
    const hash = createHash('sha256');
    let size = 0;
    // The transform recomputes the hash as bytes pass, so the sha of what actually landed is known
    // without a second read of a file that may be hundreds of megabytes.
    part.file.on('data', (chunk: Buffer) => {
      size += chunk.length;
      if (size > config.maxUploadBytes) {
        part.file.destroy(new AppError('payload_too_large', 'File exceeds the configured upload limit'));
        return;
      }
      hash.update(chunk);
    });
    await pipeline(part.file, createWriteStream(target, { flags: 'wx' }));
    filePart = { filename: part.filename, path: target, sha256: hash.digest('hex'), size };
  }

  if (!filePart) {
    await rm(tmpDir, { recursive: true, force: true }).catch(() => undefined);
    throw new AppError('bad_request', 'No file part in the request');
  }

  // A part with an empty filename is a field, not a file, and some clients send exactly that.
  // Accepting it creates a book called 未命名 with no way to tell it from a real upload.
  const filename = filePart.filename.trim();
  if (filename.length === 0) {
    await rm(tmpDir, { recursive: true, force: true }).catch(() => undefined);
    throw new AppError('bad_request', 'The file part must carry a filename');
  }

  let mode: ReceivedUpload['mode'] = 'auto';
  if (fields.mode !== undefined) {
    mode = parseWith(ModeSchema, fields.mode, 'mode');
  }
  let targetBookId: string | null = null;
  if (fields.bookId !== undefined) {
    targetBookId = parseWith(IdSchema, fields.bookId, 'bookId');
  }
  let conflict: ReceivedUpload['conflict'] = null;
  if (fields.conflict !== undefined) {
    conflict = parseWith(ConflictSchema, fields.conflict, 'conflict');
  }

  return {
    uploadId,
    tmpPath: filePart.path,
    filename,
    originalFilename: filename,
    sizeBytes: filePart.size,
    sha256: filePart.sha256,
    mode,
    targetBookId,
    charset: fields.charset ?? null,
    conflict,
  };
}

const sizeOf = async (path: string) => (await stat(path)).size;

/**
 * Contain an operator-supplied path inside the import root. Without this, `POST /import/dir` with
 * an absolute path outside the volume would move the user's files into the library — a destructive
 * outcome from what looks like a benign admin call.
 *
 * Containment is decided with `path.relative`, not by comparing string prefixes. A prefix check
 * looks equivalent and is not: the configured root arrives with forward slashes while `join`
 * produces backslashes on Windows, so `startsWith` never matched and every legitimate import was
 * refused with a 403. `relative` also collapses `..` correctly, which a prefix check cannot.
 */
export function resolveWithin(root: string, requested: string): string {
  const base = resolve(root);
  const candidate = resolve(base, requested);
  const rel = relative(base, candidate);
  if (rel.startsWith('..') || isAbsolute(rel)) {
    throw new AppError('forbidden', 'Import path must stay inside the configured import directory');
  }
  return candidate;
}

async function collectEbookFiles(dir: string, limit: number): Promise<{ path: string; name: string }[]> {
  const { readdir } = await import('node:fs/promises');
  const out: { path: string; name: string }[] = [];
  const stack = [dir];
  while (stack.length > 0 && out.length < limit) {
    const current = stack.pop()!;
    let entries;
    try {
      entries = await readdir(current, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = join(current, entry.name);
      if (entry.isDirectory()) stack.push(full);
      else if (entry.isFile() && EBOOK_EXTENSIONS.has(entry.name.split('.').pop()?.toLowerCase() ?? '')) {
        out.push({ path: full, name: entry.name });
        if (out.length >= limit) break;
      }
    }
  }
  return out;
}

