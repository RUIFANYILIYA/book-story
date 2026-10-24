import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import type { Db } from '../index.ts';
import { newId } from '../../ids.ts';

/**
 * Content-addressed storage for every byte the server keeps.
 *
 * Identity is the SHA-256 of the content, so two users uploading the same book cost one file, and
 * a *changed* book file is a different row rather than an overwrite. That is what lets a second
 * device's half-finished download keep reading a stable (fileId, sha256) while the first device
 * has already moved the book on to a new file — overwriting in place would corrupt it silently and
 * the client would never know.
 *
 * Rows are reference counted and never deleted while a book still points at them; the actual
 * unlink happens in the sweeper, after a retention window, and only via the trash directory so a
 * crash between "decided to delete" and "deleted" cannot orphan a file with no record.
 */

export interface BlobRow {
  id: string;
  sha256: string;
  size_bytes: number;
  storage_path: string;
  media_type: string;
  format: string;
  refcount: number;
  /** 1 when the bytes live outside the library, at `storage_path` (absolute). Never collected. */
  external: number;
}

const SHARD = (hex: string) => `${hex.slice(0, 2)}/${hex.slice(2, 4)}`;

export class BlobRepo {
  constructor(
    private readonly db: Db,
    private readonly libraryDir: string,
  ) {}

  /** Streaming hash; a 900 MB file must never be buffered to find out what it is. */
  static async hashFile(path: string): Promise<{ sha256: string; sizeBytes: number }> {
    const { createReadStream } = await import('node:fs');
    const { stat } = await import('node:fs/promises');
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

  existing(sha256: string): BlobRow | undefined {
    return this.db.get<BlobRow>(
      `SELECT id, sha256, size_bytes, storage_path, media_type, format, refcount, external
       FROM blobs WHERE sha256 = ? AND deleted_at IS NULL`,
      [sha256],
    );
  }

  /**
   * Registers a file that already sits at `relativePath` inside the library.
   *
   * The caller moves the bytes into place first, because the natural filesystem layout is derived
   * from the hash and you cannot name the destination before you know it.
   */
  register(input: {
    sha256: string;
    sizeBytes: number;
    relativePath: string;
    mediaType: string;
    format: string;
    refcount?: number;
    /** The path is absolute and outside the library; the sweeper must never touch it. */
    external?: boolean;
  }): BlobRow {
    const existing = this.existing(input.sha256);
    if (existing) {
      if (input.refcount !== 0) this.increment(existing.id);
      return this.require(existing.id);
    }
    const id = newId();
    this.db.run(
      `INSERT INTO blobs (id, sha256, size_bytes, storage_path, media_type, format, refcount, external, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        id,
        input.sha256,
        input.sizeBytes,
        input.relativePath,
        input.mediaType,
        input.format,
        input.refcount ?? 1,
        input.external ? 1 : 0,
        new Date().toISOString(),
      ],
    );
    return this.require(id);
  }

  /** Convenience for the small non-book payloads: SVG placeholders and user-uploaded covers. */
  async putBuffer(bytes: Buffer, mediaType: string, format: string): Promise<BlobRow> {
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    const existing = this.existing(sha256);
    if (existing) {
      this.increment(existing.id);
      return this.require(existing.id);
    }
    const relativePath = `covers/sha/${SHARD(sha256)}/${sha256}`;
    const { mkdir, writeFile } = await import('node:fs/promises');
    const absolute = `${this.libraryDir}/${relativePath}`;
    await mkdir(absolute.slice(0, absolute.lastIndexOf('/')), { recursive: true });
    await writeFile(absolute, bytes);
    return this.register({ sha256, sizeBytes: bytes.length, relativePath, mediaType, format });
  }

  byId(id: string): BlobRow | undefined {
    return this.db.get<BlobRow>(
      `SELECT id, sha256, size_bytes, storage_path, media_type, format, refcount, external
       FROM blobs WHERE id = ?`,
      [id],
    );
  }

  /** The row the watcher already made for a file at this absolute path, if any. */
  byExternalPath(path: string): BlobRow | undefined {
    return this.db.get<BlobRow>(
      `SELECT id, sha256, size_bytes, storage_path, media_type, format, refcount, external
       FROM blobs WHERE storage_path = ? AND external = 1 AND deleted_at IS NULL`,
      [path],
    );
  }

  require(id: string): BlobRow {
    const row = this.byId(id);
    if (!row) throw new Error(`blob ${id} not found`);
    return row;
  }

  increment(id: string): void {
    this.db.run('UPDATE blobs SET refcount = refcount + 1 WHERE id = ?', [id]);
  }

  decrement(id: string): number {
    return this.db.run('UPDATE blobs SET refcount = refcount - 1 WHERE id = ? AND refcount > 0', [id])
      .changes;
  }

  /**
   * Bytes this user *uploaded*, not bytes they can see. The library is shared, so the second
   * number would be nearly the total for every account and is useless as a quota basis.
   * Distinct blobs only: two files pointing at identical content occupy the disk once.
   */
  storageUsedBy(userId: string): number {
    return (
      this.db.get<{ n: number | null }>(
        `SELECT COALESCE(SUM(b.size_bytes), 0) AS n
         FROM blobs b
         WHERE b.deleted_at IS NULL
           AND b.id IN (
             SELECT DISTINCT bf.blob_id
             FROM book_files bf
             JOIN books bk ON bk.id = bf.book_id
             WHERE bk.added_by = ? AND bk.deleted_at IS NULL
           )`,
        [userId],
      )?.n ?? 0
    );
  }

  totalBytes(): number {
    return this.db.get<{ n: number | null }>("SELECT COALESCE(SUM(size_bytes),0) AS n FROM blobs WHERE deleted_at IS NULL")
      ?.n ?? 0;
  }

  /**
   * Where the bytes are. An external blob's `storage_path` is already absolute — it points at a
   * file the user owns, outside the library — so it is returned as-is rather than joined.
   */
  absolutePath(row: BlobRow): string {
    if (row.external === 1) return row.storage_path;
    return `${this.libraryDir}/${row.storage_path}`;
  }

  async readBuffer(row: BlobRow): Promise<Buffer> {
    return readFile(this.absolutePath(row));
  }
}
