import type { Db } from '../index.ts';
import { newId } from '../../ids.ts';
import { normalizeTitle, sortTitle } from '../../library/filenames.ts';
import { REVIEW_CONFIDENCE_THRESHOLD, type MetadataSource, type ParsedMetadata } from '../../metadata/types.ts';
import type { Format } from '../../metadata/sniff.ts';

export interface BookRow {
  id: string;
  title: string;
  title_norm: string;
  sort_title: string | null;
  sort_key: string;
  search_text: string;
  category_id: string | null;
  series_id: string | null;
  series_index: number | null;
  publisher: string | null;
  published_year: number | null;
  language: string | null;
  description: string | null;
  isbn: string | null;
  page_count: number | null;
  word_count: number | null;
  needs_review: number;
  metadata_source: MetadataSource;
  pinned_fields_json: string;
  has_cover: number;
  cover_blob_id: string | null;
  content_updated_at: string;
  added_at: string;
  updated_at: string;
  deleted_at: string | null;
}

export interface DuplicateMatch {
  bookId: string;
  title: string;
  /** How the match was found, so the UI can say "same content hash" rather than guessing. */
  by: 'sha256' | 'title-author';
}

/** A `book_files` row plus the title of the book holding it, which is what a dialog must show. */
export interface BookFileRow {
  id: string;
  book_id: string;
  blob_id: string;
  filename: string;
  size_bytes: number;
  sha256: string;
  format: string;
  book_title: string;
}

/**
 * Writing the catalogue.
 *
 * Two rules dominate everything else here. Pinned fields are never overwritten, because the
 * common real sequence is: ingest a messy file, an operator fixes the title by hand, then a
 * re-parse of the same file would silently undo their work. And every normalized column is
 * computed here rather than passed in, so `title_norm`, `sort_key` and the dedupe index cannot
 * disagree with each other the way they would if three call sites each computed them.
 */
export class BookRepo {
  constructor(private readonly db: Db) {}

  findById(id: string): BookRow | undefined {
    return this.db.get<BookRow>(`SELECT * FROM books WHERE id = ? AND deleted_at IS NULL`, [id]);
  }

  /** A book already holding these exact bytes. Cheapest and most certain duplicate check. */
  byBlobSha(sha256: string): BookRow | undefined {
    return this.db.get<BookRow>(
      `SELECT b.* FROM books b
       JOIN book_files bf ON bf.book_id = b.id
       WHERE bf.sha256 = ? AND b.deleted_at IS NULL
       LIMIT 1`,
      [sha256],
    );
  }

  byTitleAndAuthor(titleNorm: string, authorName: string | null): BookRow | undefined {
    if (!authorName) return undefined;
    return this.db.get<BookRow>(
      `SELECT b.* FROM books b
       JOIN book_contributors bc ON bc.book_id = b.id AND bc.role = 'author'
       JOIN contributors c ON c.id = bc.contributor_id
       WHERE b.title_norm = ? AND c.name_norm = ? AND b.deleted_at IS NULL
       LIMIT 1`,
      [titleNorm, normalizeTitle(authorName)],
    );
  }

  /**
   * Every live file already shelved under this name, case-insensitively.
   *
   * The name is the one thing a reader recognises in an upload dialog, so it is what a clash is
   * decided on — not the title, which a renamed re-release would dodge, and not the hash, which
   * is the *content* check and already has its own `duplicate_content` answer. Soft-deleted books
   * and quarantined files do not count: their bytes are on their way out and a name held by a
   * tombstone is not a name anyone is reading.
   */
  findFilesByName(filename: string): BookFileRow[] {
    return this.db.all<BookFileRow>(
      `SELECT bf.id, bf.book_id, bf.blob_id, bf.filename, bf.size_bytes, bf.sha256, bf.format,
              b.title AS book_title
         FROM book_files bf
         JOIN books b ON b.id = bf.book_id
        WHERE bf.filename = ? COLLATE NOCASE
          AND bf.status = 'ready'
          AND b.deleted_at IS NULL
        ORDER BY bf.created_at`,
      [filename],
    );
  }

  /**
   * Drops file rows outright; the bytes are left to the sweeper.
   *
   * A row delete rather than a tombstone on purpose: `book_files` is a join row, not user data,
   * and the retention machinery keys off reachability — once nothing live points at the blob it
   * is retired after the retention window and reclaimed through the trash directory, which is
   * exactly the lifecycle a replaced file should have. Refcount is deliberately untouched: the
   * sweeper collects by reachability, and a `decrement` here would only desynchronise the counter
   * from the rows that actually exist.
   */
  deleteFiles(fileIds: string[]): number {
    if (fileIds.length === 0) return 0;
    const placeholders = fileIds.map(() => '?').join(',');
    return this.db.run(`DELETE FROM book_files WHERE id IN (${placeholders})`, fileIds).changes;
  }

  upsertFromMetadata(input: {
    meta: ParsedMetadata;
    /** Hash of the file being ingested; enables the certain duplicate check before any parsing. */
    sha256: string;
    filename: string;
    userId: string | null;
    /** Explicit target wins; otherwise a title+author match may attach the file instead. */
    targetBookId?: string | null;
    mode: 'auto' | 'create' | 'attach' | 'replace';
    pinnedFields?: string[];
  }): { bookId: string; created: boolean; matchedBy: DuplicateMatch['by'] | null } {
    const { meta } = input;
    const title = meta.title ?? '未命名';
    const titleNorm = normalizeTitle(title);
    const author = meta.contributors.find((c) => c.role === 'author')?.name ?? null;
    const now = new Date().toISOString();

    let target = input.targetBookId ? this.findById(input.targetBookId) : undefined;
    let matchedBy: DuplicateMatch['by'] | null = null;

    if (!target && input.mode !== 'create') {
      const byHash = this.byBlobSha(input.sha256);
      if (byHash) {
        target = byHash;
        matchedBy = 'sha256';
      } else {
        const byTitle = this.byTitleAndAuthor(titleNorm, author);
        if (byTitle) {
          target = byTitle;
          matchedBy = 'title-author';
        }
      }
    }

    const pinned = new Set(
      input.pinnedFields ?? readPinned(target?.pinned_fields_json),
    );
    const keep = (field: string, incoming: unknown, current: unknown) =>
      pinned.has(field) && current !== null && current !== undefined ? current : (incoming ?? current);

    if (!target) {
      const id = newId();
      const seriesId = meta.series ? this.ensureSeries(meta.series) : null;
      this.db.run(
        `INSERT INTO books (id, title, title_norm, sort_title, sort_key, category_id, series_id, series_index,
                            publisher, published_year, language, description, isbn, page_count, word_count,
                            needs_review, metadata_source, pinned_fields_json, has_cover, cover_blob_id,
                            content_updated_at, added_at, updated_at, added_by, deleted_at)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)`,
        [
          id,
          title,
          titleNorm,
          sortTitle(title),
          author ? `${author} — ${title}` : title,
          null,
          seriesId,
          meta.seriesIndex ?? null,
          meta.publisher ?? null,
          meta.publishedYear ?? null,
          meta.language ?? null,
          meta.description ?? null,
          meta.isbn ?? null,
          meta.pageCount ?? null,
          meta.wordCount ?? null,
          meta.confidence < REVIEW_CONFIDENCE_THRESHOLD ? 1 : 0,
          meta.source,
          JSON.stringify(input.pinnedFields ?? []),
          0,
          null,
          now,
          now,
          now,
          input.userId,
        ],
      );
      target = this.findById(id)!;
      this.setContributors(id, meta.contributors);
      this.addTags(id, meta.tags ?? [], input.userId);
      return { bookId: id, created: true, matchedBy: null };
    }

    // The sort key has to follow a hand-typed author rather than the name the file declared, or the
    // shelf keeps ordering the book under an author nobody agreed to.
    const sortAuthor = pinned.has('authors') ? (this.authorsOf(target.id)[0] ?? null) : author;

    this.db.run(
      `UPDATE books SET title = ?, title_norm = ?, sort_title = ?, sort_key = ?, series_id = ?, series_index = ?,
                        publisher = ?, published_year = ?, language = ?, description = ?, isbn = ?,
                        page_count = ?, word_count = ?, metadata_source = ?, updated_at = ?
       WHERE id = ?`,
      [
        keep('title', title, target.title) as string,
        titleNorm,
        sortTitle(keep('title', title, target.title) as string),
        sortAuthor
          ? `${sortAuthor} — ${keep('title', title, target.title)}`
          : (keep('title', title, target.title) as string),
        meta.series ? this.ensureSeries(meta.series) : target.series_id,
        keep('seriesIndex', meta.seriesIndex, target.series_index) as number | null,
        keep('publisher', meta.publisher, target.publisher) as string | null,
        keep('publishedYear', meta.publishedYear, target.published_year) as number | null,
        keep('language', meta.language, target.language) as string | null,
        keep('description', meta.description, target.description) as string | null,
        keep('isbn', meta.isbn, target.isbn) as string | null,
        keep('pageCount', meta.pageCount, target.page_count) as number | null,
        keep('wordCount', meta.wordCount, target.word_count) as number | null,
        meta.source,
        now,
        target.id,
      ],
    );
    // Contributors and tags are written here rather than left as a second call the caller has to
    // remember. A partial upsert is the failure shape worth designing out: every caller that
    // forgets it loses the author silently, and the response then looks exactly like a file that
    // simply declared none.
    this.setContributors(target.id, this.keepManualAuthors(target.id, meta.contributors, pinned));
    this.addTags(target.id, meta.tags ?? [], input.userId);
    return { bookId: target.id, created: false, matchedBy };
  }

  /**
   * A re-parse must not undo a hand-typed author, and must still refresh the roles the edit box
   * never showed. So when `authors` is pinned the manual names survive and everything else —
   * translator, illustrator — comes from the file. An empty manual list is also a decision: someone
   * cleared it deliberately, so the file does not get to put an author back.
   */
  private keepManualAuthors(
    bookId: string,
    parsed: ParsedMetadata['contributors'],
    pinned: Set<string>,
  ): ParsedMetadata['contributors'] {
    if (!pinned.has('authors')) return parsed;
    const kept = this.authorsOf(bookId).map((name) => ({ name, role: 'author' as const }));
    return [...kept, ...parsed.filter((c) => c.role !== 'author')];
  }

  ensureSeries(name: string): string {
    const norm = normalizeTitle(name);
    const existing = this.db.get<{ id: string }>('SELECT id FROM series WHERE name_norm = ?', [norm]);
    if (existing) return existing.id;
    const id = newId();
    this.db.run('INSERT INTO series (id, name, name_norm, created_at) VALUES (?, ?, ?, ?)', [
      id,
      name.trim(),
      norm,
      new Date().toISOString(),
    ]);
    return id;
  }

  ensureContributor(name: string, sortName?: string | null): string {
    const norm = normalizeTitle(name);
    const existing = this.db.get<{ id: string }>('SELECT id FROM contributors WHERE name_norm = ?', [norm]);
    const id = existing?.id ?? newId();
    if (!existing) {
      this.db.run(
        `INSERT INTO contributors (id, name, name_norm, sort_name, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?)`,
        [id, name.trim(), norm, sortName ?? null, new Date().toISOString(), new Date().toISOString()],
      );
    }
    return id;
  }

  /** Names in display order. The list query denormalizes this itself; the detail response needs
   *  the same thing from the same join, so the two views cannot drift on who the author is. */
  authorsOf(bookId: string): string[] {
    return this.db
      .all<{ name: string }>(
        `SELECT c.name FROM book_contributors bc JOIN contributors c ON c.id = bc.contributor_id
          WHERE bc.book_id = ? AND bc.role = 'author' ORDER BY bc.position`,
        [bookId],
      )
      .map((r) => r.name);
  }

  /**
   * Replaces only the `author` rows.
   *
   * `setContributors` deletes every role at once, which is right for a parse that supplies the
   * whole list and wrong for an edit box that shows one role: fixing a mistyped name would quietly
   * erase the translator an EPUB declared.
   */
  setAuthors(bookId: string, names: string[]): void {
    this.db.tx(() => {
      this.db.run(
        `DELETE FROM book_contributors WHERE book_id = ? AND role = 'author'`,
        [bookId],
      );
      const seen = new Set<string>();
      let position = 0;
      for (const raw of names) {
        const name = raw.trim();
        // "刘慈欣 / 刘慈欣" is one author typed twice, not a two-person work.
        if (!name || seen.has(normalizeTitle(name))) continue;
        seen.add(normalizeTitle(name));
        this.db.run(
          'INSERT INTO book_contributors (book_id, contributor_id, role, position) VALUES (?, ?, ?, ?)',
          [bookId, this.ensureContributor(name), 'author', position++],
        );
      }
    });
  }

  setContributors(bookId: string, contributors: ParsedMetadata['contributors']): void {
    this.db.tx(() => {
      this.db.run('DELETE FROM book_contributors WHERE book_id = ?', [bookId]);
      contributors.forEach((person, index) => {
        const cid = this.ensureContributor(person.name, person.sortName);
        this.db.run(
          'INSERT INTO book_contributors (book_id, contributor_id, role, position) VALUES (?, ?, ?, ?)',
          [bookId, cid, person.role, index],
        );
      });
    });
  }

  addTags(bookId: string, tags: string[], userId: string | null): void {
    if (tags.length === 0) return;
    this.db.tx(() => {
      for (const raw of tags) {
        const name = raw.trim();
        if (!name) continue;
        const norm = normalizeTitle(name);
        let tag = this.db.get<{ id: string }>('SELECT id FROM tags WHERE name_norm = ?', [norm]);
        if (!tag) {
          const id = newId();
          this.db.run(
            'INSERT INTO tags (id, name, name_norm, created_at) VALUES (?, ?, ?, ?)',
            [id, name, norm, new Date().toISOString()],
          );
          tag = { id };
        }
        this.db.run(
          `INSERT OR IGNORE INTO book_tags (book_id, tag_id, added_by, added_at) VALUES (?, ?, ?, ?)`,
          [bookId, tag.id, userId, new Date().toISOString()],
        );
      }
      this.refreshTagCounters();
    });
  }

  refreshTagCounters(): void {
    this.db.run(
      `UPDATE tags SET usage_count = (SELECT count(*) FROM book_tags bt WHERE bt.tag_id = tags.id)`,
    );
  }

  /** `search_text` is what the trigram index and the LIKE fallback both read. */
  refreshSearchText(bookId: string): void {
    const book = this.db.get<{ id: string; title: string; publisher: string | null; description: string | null; series: string | null }>(
      `SELECT b.id, b.title, b.publisher, b.description, s.name AS series
       FROM books b LEFT JOIN series s ON s.id = b.series_id WHERE b.id = ?`,
      [bookId],
    );
    if (!book) return;
    const authors = this.db
      .all<{ name: string }>(
        `SELECT c.name FROM book_contributors bc JOIN contributors c ON c.id = bc.contributor_id
         WHERE bc.book_id = ? ORDER BY bc.position`,
        [bookId],
      )
      .map((r) => r.name);
    const tags = this.db
      .all<{ name: string }>(
        `SELECT t.name FROM book_tags bt JOIN tags t ON t.id = bt.tag_id WHERE bt.book_id = ?`,
        [bookId],
      )
      .map((r) => r.name);
    const haystack = [book.title, ...authors, ...tags, book.series, book.publisher, book.description]
      .filter(Boolean)
      .join(' ');
    this.db.run('UPDATE books SET search_text = ? WHERE id = ?', [normalizeTitle(haystack), bookId]);
  }

  attachFile(input: {
    bookId: string;
    blobId: string;
    sha256: string;
    sizeBytes: number;
    format: Format;
    mediaType: string;
    filename: string;
    charset?: string | null;
    pageCount?: number | null;
    drm?: boolean;
    makePrimary?: boolean;
  }): string {
    const now = new Date().toISOString();
    const id = newId();
    const isPrimary = input.makePrimary ?? !this.hasAnyFile(input.bookId);

    // These exact bytes are already filed under this book — what a `mode=replace` re-import of an
    // unchanged file produces. Attaching again is a re-assertion, not a second row:
    // `ux_book_files_pair (book_id, blob_id)` refuses the insert outright, and that refusal used
    // to surface as a 500 on the most ordinary retry there is. The stored filename is left as it
    // was first filed: a file id is a download key, and the name already in flight on another
    // device is not this request's to change.
    const filed = this.db.get<{ id: string }>(
      'SELECT id FROM book_files WHERE book_id = ? AND blob_id = ?',
      [input.bookId, input.blobId],
    );
    if (filed) {
      if (isPrimary) {
        this.db.run('UPDATE book_files SET is_primary = 0 WHERE book_id = ? AND id != ?', [
          input.bookId,
          filed.id,
        ]);
        this.db.run('UPDATE book_files SET is_primary = 1, updated_at = ? WHERE id = ?', [now, filed.id]);
      }
      this.refreshFileStats(input.bookId);
      return filed.id;
    }

    if (isPrimary) this.db.run('UPDATE book_files SET is_primary = 0 WHERE book_id = ?', [input.bookId]);
    this.db.run(
      `INSERT INTO book_files (id, book_id, blob_id, format, media_type, filename, size_bytes, sha256,
                               charset, page_count, drm, is_primary, status, created_at, updated_at)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'ready', ?, ?)`,
      [
        id,
        input.bookId,
        input.blobId,
        input.format,
        input.mediaType,
        input.filename,
        input.sizeBytes,
        input.sha256,
        input.charset ?? null,
        input.pageCount ?? null,
        input.drm ? 1 : 0,
        isPrimary ? 1 : 0,
        now,
        now,
      ],
    );
    this.refreshFileStats(input.bookId);
    return id;
  }

  hasAnyFile(bookId: string): boolean {
    return (this.db.get<{ n: number }>('SELECT count(*) AS n FROM book_files WHERE book_id = ?', [bookId])?.n ?? 0) > 0;
  }

  setCover(bookId: string, blobId: string | null): void {
    const now = new Date().toISOString();
    this.db.run(
      'UPDATE books SET cover_blob_id = ?, has_cover = ?, cover_updated_at = ?, updated_at = ? WHERE id = ?',
      [blobId, blobId ? 1 : 0, now, now, bookId],
    );
  }

  refreshFileStats(bookId: string): void {
    this.db.run(
      `UPDATE books SET storage_bytes = (SELECT COALESCE(SUM(size_bytes),0) FROM book_files WHERE book_id = books.id),
                        file_count = (SELECT count(*) FROM book_files WHERE book_id = books.id),
                        content_updated_at = ?
       WHERE id = ?`,
      [new Date().toISOString(), bookId],
    );
  }

  list(): BookRow[] {
    return this.db.all<BookRow>('SELECT * FROM books WHERE deleted_at IS NULL ORDER BY added_at DESC');
  }
}

/** Fields an operator has fixed by hand; a re-parse must never silently undo them. */
function readPinned(json: string | undefined): string[] {
  if (!json) return [];
  try {
    const parsed: unknown = JSON.parse(json);
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : [];
  } catch {
    return [];
  }
}
