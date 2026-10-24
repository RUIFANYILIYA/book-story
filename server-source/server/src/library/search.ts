import type { Db, SqlValue } from '../db/index.ts';
import { AppError } from '../errors.ts';
import { escapeLike } from '../db/repositories/user.repo.ts';
import { normalizeTitle } from './filenames.ts';
import { keysetCondition, limitOf, tailValues, SORT_KEYS, type CursorCodec, type Paged, type SortKey } from '../http/pagination.ts';

export interface BrowseFilters {
  q?: string | null;
  format?: string[];
  tag?: string[];
  notTag?: string[];
  categoryPath?: string | null;
  seriesId?: string | null;
  collectionId?: string | null;
  contributorId?: string | null;
  language?: string | null;
  status?: string | null;
  needsReview?: boolean | null;
  missingCover?: boolean | null;
  minYear?: number | null;
  maxYear?: number | null;
}

export interface BrowseOptions {
  filters: BrowseFilters;
  sort: SortKey;
  order: 'asc' | 'desc';
  limit: number;
  cursor: string | null;
}

export interface BookSummaryRow extends Record<string, unknown> {
  id: string;
  title: string;
  sort_key: string;
  published_year: number | null;
  language: string | null;
  has_cover: number;
  cover_updated_at: string | null;
  content_updated_at: string;
  added_at: string;
  storage_bytes: number;
  file_count: number;
  needs_review: number;
  series_index: number | null;
  series_name: string | null;
  authors: string | null;
  formats: string | null;
  my_status: string | null;
  my_percent: number | null;
  my_last_read: string | null;
  category_id: string | null;
}

/**
 * The browse query.
 *
 * Nothing here is assembled by pasting user text into SQL. Every filter value is a bound
 * parameter, every column name comes from a literal in this file, and the sort key is a lookup
 * into `SORT_KEYS` rather than an interpolated identifier — which is the one place a "dynamic
 * ORDER BY" usually turns into an injection.
 *
 * Search is routed by query length on purpose. The trigram tokenizer needs three characters, so a
 * one- or two-character Chinese query — extremely common, since that is most personal names and
 * many titles — would return nothing if it were handed to FTS. Those go to LIKE on the normalized
 * column instead, which is cheap at this scale and answers the question correctly.
 */
export class BookSearch {
  constructor(
    private readonly db: Db,
    private readonly codec: CursorCodec,
  ) {}

  browse(userId: string, options: BrowseOptions): Paged<BookSummaryRow> {
    const limit = limitOf(options.limit);
    const where: string[] = ['b.deleted_at IS NULL'];
    const params: SqlValue[] = [];

    // Correlated subqueries rather than joins: joining contributors, files and tags multiplies
    // rows and turns the page size into something that depends on the data.
    const select = `
      SELECT b.id, b.title, b.sort_key, b.published_year, b.language, b.has_cover,
             b.cover_updated_at, b.content_updated_at, b.updated_at, b.added_at, b.storage_bytes, b.file_count,
             b.needs_review, b.series_index, b.category_id,
             s.name AS series_name,
             -- The ORDER BY belongs *inside* group_concat: a correlated subquery's own ORDER BY is
             -- not guaranteed to feed the aggregate in that order, which is why the shelf used to
             -- list authors in an arbitrary order while the detail page showed them by position.
             (SELECT group_concat(c.name, ' / ' ORDER BY bc.position, c.name)
                FROM book_contributors bc JOIN contributors c ON c.id = bc.contributor_id
               WHERE bc.book_id = b.id AND bc.role = 'author') AS authors,
             (SELECT group_concat(bf.format, ',') FROM book_files bf WHERE bf.book_id = b.id) AS formats,
             ub.status AS my_status, ub.progress_percent AS my_percent, ub.last_read_at AS my_last_read,
             ${SORT_KEYS[options.sort].map((column, index) => `${column} AS k${index}`).join(', ')}
        FROM books b
        LEFT JOIN series s ON s.id = b.series_id
        LEFT JOIN user_book_states ub ON ub.book_id = b.id AND ub.user_id = ?`;
    params.push(userId);

    const from: string[] = [];
    const f = options.filters;

    if (f.format?.length) {
      where.push(`EXISTS (SELECT 1 FROM book_files bf2 WHERE bf2.book_id = b.id AND bf2.format IN (${placeholders(f.format.length)}))`);
      params.push(...f.format);
    }
    if (f.tag?.length) {
      for (const tag of f.tag) {
        where.push(`EXISTS (SELECT 1 FROM book_tags bt JOIN tags t ON t.id = bt.tag_id
                              WHERE bt.book_id = b.id AND t.name_norm = ?)`);
        params.push(normalizeTitle(tag));
      }
    }
    if (f.notTag?.length) {
      for (const tag of f.notTag) {
        where.push(`NOT EXISTS (SELECT 1 FROM book_tags bt JOIN tags t ON t.id = bt.tag_id
                                 WHERE bt.book_id = b.id AND t.name_norm = ?)`);
        params.push(normalizeTitle(tag));
      }
    }
    if (f.categoryPath) {
      // Materialized path prefix match covers the whole subtree in one index scan.
      where.push(
        `b.category_id IN (SELECT id FROM categories WHERE path LIKE ? ESCAPE '\\' OR path = ?)`,
      );
      const needle = escapeLike(f.categoryPath);
      params.push(`${needle}%`, f.categoryPath);
    }
    if (f.seriesId) {
      where.push('b.series_id = ?');
      params.push(f.seriesId);
    }
    if (f.collectionId) {
      from.push('JOIN collection_books cb ON cb.book_id = b.id');
      where.push('cb.collection_id = ?');
      params.push(f.collectionId);
    }
    if (f.contributorId) {
      where.push(`EXISTS (SELECT 1 FROM book_contributors bc3 WHERE bc3.book_id = b.id AND bc3.contributor_id = ?)`);
      params.push(f.contributorId);
    }
    if (f.language) {
      where.push('b.language LIKE ? ESCAPE \'\\\'');
      params.push(`${escapeLike(f.language)}%`);
    }
    if (f.status) {
      where.push('ub.status = ?');
      params.push(f.status);
    }
    if (f.needsReview) where.push('b.needs_review = 1');
    if (f.missingCover) where.push('b.has_cover = 0');
    if (f.minYear !== null && f.minYear !== undefined) {
      where.push('b.published_year >= ?');
      params.push(f.minYear);
    }
    if (f.maxYear !== null && f.maxYear !== undefined) {
      where.push('b.published_year <= ?');
      params.push(f.maxYear);
    }

    if (f.q && f.q.trim().length > 0) {
      const query = f.q.trim();
      // search_text is stored normalized, so the term has to be normalized the same way to be
      // comparable. That has a trap: a punctuation-only query normalizes to the empty string, and
      // an empty term inside `%%` matches every row - "???" would then return the whole library.
      // A query with nothing searchable in it has no results, which is what this encodes.
      const needle = normalizeTitle(query);
      if (needle.length === 0) {
        where.push('0');
      } else if ([...needle].length >= 3) {
        // Quoted, so the trigram index sees one phrase rather than a query grammar the user did
        // not intend to write. Below three characters trigrams cannot match at all, hence LIKE.
        where.push('b.id IN (SELECT book_id FROM books_fts WHERE books_fts MATCH ?)');
        params.push(`"${needle.replaceAll('"', '""')}"`);
      } else {
        where.push('b.search_text LIKE ? ESCAPE \'\\\'');
        params.push(`%${escapeLike(needle)}%`);
      }
    }

    const keyset = keysetCondition(this.codec, options.cursor, options.sort, options.order);
    const direction = options.order === 'asc' ? 'ASC' : 'DESC';
    const orderBy = SORT_KEYS[options.sort]
      .map((column, index) => `${column} ${index === 0 ? direction : 'ASC'}`)
      .join(', ');

    const sql = `${select}
      ${from.join(' ')}
      WHERE ${where.join(' AND ')}
      ${keyset.sql}
      ORDER BY ${orderBy}
      LIMIT ?`;
    const rows = this.db.all<BookSummaryRow>(sql, [...params, ...keyset.params, limit + 1]);

    const hasMore = rows.length > limit;
    const items = hasMore ? rows.slice(0, limit) : rows;
    return {
      items,
      hasMore,
      nextCursor:
        hasMore && items.length > 0
          ? this.codec.encode({
              sort: options.sort,
              order: options.order,
              after: tailValues(items, options.sort),
            })
          : null,
    };
  }

  /**
   * Counts for the filter chips. Global rather than filtered, which is a deliberate limitation:
   * recomputing per-facet counts under every other active filter needs a UNION over the whole
   * filter matrix, and getting it to agree with the page it labels is more work than the feature
   * is worth for a personal library.
   */
  facets(): { formats: Record<string, number>; languages: Record<string, number> } {
    const formats: Record<string, number> = {};
    for (const row of this.db.all<{ format: string; n: number }>(
      `SELECT bf.format AS format, count(DISTINCT bf.book_id) AS n
         FROM book_files bf JOIN books b ON b.id = bf.book_id
        WHERE b.deleted_at IS NULL GROUP BY bf.format`,
    )) {
      formats[row.format] = row.n;
    }
    const languages: Record<string, number> = {};
    for (const row of this.db.all<{ language: string | null; n: number }>(
      `SELECT b.language AS language, count(*) AS n FROM books b
        WHERE b.deleted_at IS NULL AND b.language IS NOT NULL GROUP BY b.language`,
    )) {
      if (row.language) languages[row.language] = row.n;
    }
    return { formats, languages };
  }
}

function placeholders(count: number): string {
  if (count < 1 || count > 100) throw new AppError('bad_request', 'too many values in a filter');
  return Array.from({ length: count }, () => '?').join(', ');
}
