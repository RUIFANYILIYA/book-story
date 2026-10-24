import { createHash } from 'node:crypto';
import { copyFile, mkdir, rename, stat, unlink } from 'node:fs/promises';
import { dirname } from 'node:path';
import { createReadStream } from 'node:fs';
import type { Config } from '../config.ts';
import type { Db } from '../db/index.ts';
import { AppError } from '../errors.ts';
import { newId } from '../ids.ts';
import { BlobRepo } from '../db/repositories/blob.repo.ts';
import { BookRepo } from '../db/repositories/book.repo.ts';
import { UserRepo } from '../db/repositories/user.repo.ts';
import { identify, parseFile } from '../metadata/index.ts';
import { withFilenameFallback, type ParsedMetadata } from '../metadata/types.ts';
import { parseFilename } from './filenames.ts';
import { detectImageType, placeholderCoverSvg, SVG_MEDIA_TYPE } from './covers.ts';

export interface IngestInput {
  /** Temporary file already fully written and closed. ingestFile does not read a live stream. */
  sourcePath: string;
  filename: string;
  userId: string | null;
  mode: 'auto' | 'create' | 'attach' | 'replace';
  targetBookId?: string | null;
  /** Fields the operator has fixed by hand; a re-parse must not overwrite them. */
  pinnedFields?: string[];
  /** Forces a charset for TXT instead of trusting detection. */
  charset?: string | null;
  /**
   * Catalogue the file where it is instead of moving it into the library.
   *
   * For a watched folder that is the whole point: the bytes stay the user's, the library only
   * records the path, and nothing is duplicated on disk. The caller is responsible for the file
   * being readable at `sourcePath` for as long as the book exists.
   */
  link?: boolean;
}

export interface IngestResult {
  bookId: string;
  fileId: string;
  blobId: string;
  format: string;
  title: string;
  created: boolean;
  deduped: boolean;
  needsReview: boolean;
  warnings: string[];
  matchedBy: 'sha256' | 'title-author' | null;
}

export interface IngestDeps {
  db: Db;
  config: Config;
}

/** What the server will try to read. Shared by the upload route and the watch folder. */
export const EBOOK_EXTENSIONS = new Set([
  'epub', 'txt', 'pdf', 'mobi', 'azw', 'azw3', 'fb2', 'cbz', 'docx', 'htmlz',
]);

/**
 * Turn a file on disk into a book.
 *
 * The order is deliberate and the hash comes first for two reasons that are not about
 * deduplication: it is the only stable identity the file will ever have, so it must be computed
 * before anything names a path after it; and it lets a re-upload of a file already in the library
 * be recognised and rejected *before* up to a gigabyte is rewritten to a new location.
 *
 * A parse failure never loses the file. The content is stored first and metadata is derived
 * afterwards, so the worst outcome is a book flagged for review that still downloads and reads
 * correctly — which is the right trade for a library where the file is the thing the user wanted.
 */
export async function ingestFile(deps: IngestDeps, input: IngestInput): Promise<IngestResult> {
  const { db, config } = deps;
  const books = new BookRepo(db);
  const blobs = new BlobRepo(db, config.libraryDir);
  const users = new UserRepo(db);

  const { sha256, sizeBytes } = await hashAndStat(input.sourcePath);

  const existingBlob = blobs.existing(sha256);
  const holder = existingBlob ? books.byBlobSha(sha256) : undefined;
  if (holder && existingBlob && input.mode !== 'replace') {
    // The exact bytes are already shelved somewhere. Reporting that beats creating a second
    // book that points at the same blob and confuses the dedupe index forever.
    throw new AppError('duplicate_content', 'These bytes are already in the library', {
      details: { bookId: holder.id, title: holder.title, blobId: existingBlob.id },
    });
  }

  if (input.userId) {
    const user = users.byId(input.userId);
    if (user?.quota_bytes !== null && user?.quota_bytes !== undefined && !existingBlob) {
      const used = blobs.storageUsedBy(user.id);
      if (used + sizeBytes > user.quota_bytes) {
        throw new AppError('insufficient_storage', 'Upload would exceed this account quota', {
          details: { usedBytes: used, quotaBytes: user.quota_bytes, incomingBytes: sizeBytes },
        });
      }
    }
  }

  const link = input.link === true;
  // A linked file is already at its final home, so the hash-derived layout is not used and the
  // move below is skipped — that is what keeps a watched folder from costing a second copy.
  const relativePath = link ? input.sourcePath : `blobs/${shard(sha256)}/${sha256}`;
  const absolutePath = link ? input.sourcePath : `${config.libraryDir}/${relativePath}`;

  if (!existingBlob && !link) {
    await mkdir(dirname(absolutePath), { recursive: true });
    await moveIntoPlace(input.sourcePath, absolutePath);
  }

  const identified = await identify(absolutePath, input.filename);
  const blob =
    existingBlob ??
    blobs.register({
      sha256,
      sizeBytes,
      relativePath,
      mediaType: identified.sniff.mediaType,
      format: identified.format,
      // A blob written but not yet attached to a book must not be collected mid-import.
      refcount: 1,
      external: link,
    });

  const parsed = await parseFile(absolutePath, identified.format, input.filename);
  const hint = parseFilename(input.filename);
  const meta = withFilenameFallback(parsed, { title: hint.title, author: hint.author });
  if (input.charset) meta.charset = input.charset;

  const upsert = books.upsertFromMetadata({
    meta,
    sha256,
    filename: input.filename,
    userId: input.userId,
    targetBookId: input.targetBookId ?? null,
    mode: input.mode,
    pinnedFields: input.pinnedFields,
  });
  const bookId = upsert.bookId;

  const fileId = books.attachFile({
    bookId,
    blobId: blob.id,
    sha256,
    sizeBytes,
    format: identified.format,
    mediaType: identified.sniff.mediaType,
    filename: safeDownloadName(input.filename, identified.format),
    charset: meta.charset ?? null,
    pageCount: meta.pageCount ?? null,
    drm: meta.drm,
    makePrimary: upsert.created || input.mode === 'replace',
  });

  const coverBlobId = await storeCover(db, config, meta, bookId, blob.id);
  if (coverBlobId) books.setCover(bookId, coverBlobId);
  books.refreshSearchText(bookId);

  const stored = books.findById(bookId);
  const needsReview =
    meta.confidence < 0.6 || (stored?.needs_review === 1 && meta.source === 'filename');
  db.run('UPDATE books SET needs_review = ? WHERE id = ?', [needsReview ? 1 : 0, bookId]);

  return {
    bookId,
    fileId,
    blobId: blob.id,
    format: identified.format,
    title: stored?.title ?? '未命名',
    created: upsert.created,
    deduped: existingBlob !== undefined,
    needsReview,
    warnings: meta.warnings,
    matchedBy: upsert.matchedBy,
  };
}

/**
 * Cover resolution, in the order a human would expect:
 * the container's own art, then a deterministic generated tile. Never nothing — a shelf full of
 * empty rectangles reads as a broken server even when every book is perfectly intact.
 */
async function storeCover(
  db: Db,
  config: Config,
  meta: ParsedMetadata,
  bookId: string,
  blobId: string,
): Promise<string | null> {
  const blobs = new BlobRepo(db, config.libraryDir);
  if (meta.cover && meta.cover.bytes.length > 0) {
    const detected = detectImageType(new Uint8Array(meta.cover.bytes));
    if (!detected) {
      db.run('INSERT INTO ingest_jobs (id, kind, book_id, state, created_at) VALUES (?, ?, ?, ?, ?)', [
        newId(),
        'cover',
        bookId,
        'failed',
        new Date().toISOString(),
      ]);
      // Reported rather than swallowed: the container claimed a cover and it was not an image.
      return null;
    }
    const row = await blobs.putBuffer(Buffer.from(meta.cover.bytes), detected.mediaType, 'cover');
    return row.id;
  }

  const svg = placeholderCoverSvg({
    title: meta.title ?? '未命名',
    author: meta.contributors.find((c) => c.role === 'author')?.name ?? null,
    seed: blobId,
  });
  const row = await blobs.putBuffer(svg, SVG_MEDIA_TYPE, 'cover-placeholder');
  return row.id;
}

/** Streaming hash; never holds a whole book in memory. */
export async function hashAndStat(path: string): Promise<{ sha256: string; sizeBytes: number }> {
  const info = await stat(path);
  const hash = createHash('sha256');
  await new Promise<void>((resolve, reject) => {
    const stream = createReadStream(path, { highWaterMark: 1 << 20 });
    stream.on('data', (chunk: string | Buffer) => hash.update(chunk));
    stream.on('error', reject);
    stream.on('end', () => resolve());
  });
  return { sha256: hash.digest('hex'), sizeBytes: info.size };
}

const shard = (hex: string) => `${hex.slice(0, 2)}/${hex.slice(2, 4)}`;

/**
 * rename() is the fast path and keeps the operation atomic. It fails EXDEV when the upload landed
 * on a different filesystem — which happens whenever /data/library is a volume mount but the
 * container tmp directory is the overlay — so that case degrades to copy+unlink rather than
 * failing the import.
 */
async function moveIntoPlace(from: string, to: string): Promise<void> {
  try {
    await rename(from, to);
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code !== 'EXDEV') throw err;
    await copyFile(from, to);
    await unlink(from).catch(() => undefined);
  }
}

/**
 * The name a client saves the file as. Stripped of path separators and control characters here,
 * because it is the only filename the user ever sees and it arrives from an uploaded header.
 */
export function safeDownloadName(filename: string, format: string): string {
  const base = filename.split(/[\\/]/u).pop() ?? 'book';
  const cleaned = base.replace(/[\u0000-\u001f<>:"/\\|?*]/gu, '').trim().slice(0, 180);
  const withoutExt = cleaned.replace(/\.[^.]{1,8}$/u, '');
  const wanted = withoutExt.length > 0 ? withoutExt : 'book';
  const extension = EXT_BY_FORMAT[format] ?? format;
  return `${wanted}.${extension}`;
}

/**
 * `三体.txt` with n = 1 becomes `三体 (1).txt` — the shape every file manager uses for "keep
 * both". Only the *name* is numbered; the extension is whatever the caller passed, so a caller
 * that already normalised the name (safeDownloadName) keeps its canonical extension.
 */
export function numberedFilename(filename: string, n: number): string {
  const dot = filename.lastIndexOf('.');
  const hasExt = dot > 0 && dot >= filename.length - 9;
  const base = hasExt ? filename.slice(0, dot) : filename;
  const ext = hasExt ? filename.slice(dot) : '';
  return `${base} (${n})${ext}`;
}

const EXT_BY_FORMAT: Record<string, string> = {
  epub: 'epub',
  txt: 'txt',
  pdf: 'pdf',
  mobi: 'mobi',
  azw: 'azw',
  azw3: 'azw3',
  kfx: 'kfx',
  fb2: 'fb2',
  cbz: 'cbz',
  docx: 'docx',
  doc: 'doc',
  htmlz: 'htmlz',
};
