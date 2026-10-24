import type { Format } from './sniff.ts';

/**
 * What a parser is allowed to claim about a file it has just read.
 *
 * `source` and `confidence` are not decoration: they decide whether the ingest writes the field
 * over an existing value, and whether the book is flagged for human review. A title lifted from a
 * filename is a guess, and treating it as equivalent to `dc:title` is how a library ends up with
 * three hundred entries called `book(1)`.
 */

export type MetadataSource =
  | 'epub'
  | 'fb2'
  | 'mobi'
  | 'pdf'
  | 'docx'
  | 'cbz'
  | 'txt'
  | 'filename'
  | 'manual'
  | 'import';

export interface ContributorHint {
  name: string;
  role: 'author' | 'translator' | 'illustrator' | 'editor' | 'narrator' | 'compiler';
  /** `opf:file-as`, i.e. "三体, 刘" style sort keys, when the file supplies one. */
  sortName?: string | null;
}

export interface ParsedMetadata {
  format: Format;
  title: string | null;
  subtitle?: string | null;
  sortTitle?: string | null;
  contributors: ContributorHint[];
  series?: string | null;
  seriesIndex?: number | null;
  tags?: string[];
  publisher?: string | null;
  publishedYear?: number | null;
  language?: string | null;
  description?: string | null;
  isbn?: string | null;
  pageCount?: number | null;
  wordCount?: number | null;
  /** Character count for TXT, which is what the reader's progress maths actually wants. */
  charCount?: number | null;
  /** Raw encoded cover bytes, or null when the container has none. */
  cover?: { bytes: Buffer; mediaType: string } | null;
  source: MetadataSource;
  /** 0..1. Below the review threshold the book is flagged `needs_review`. */
  confidence: number;
  /** Parse-time problems worth showing an operator, e.g. "OPF spine referenced a missing file". */
  warnings: string[];
  drm: boolean;
  /** TXT only: the charset that survived detection, persisted so it is never re-guessed. */
  charset?: string | null;
}

export const REVIEW_CONFIDENCE_THRESHOLD = 0.6;

export function emptyMetadata(format: Format, source: MetadataSource): ParsedMetadata {
  return {
    format,
    title: null,
    contributors: [],
    tags: [],
    cover: null,
    source,
    confidence: 0,
    warnings: [],
    drm: false,
  };
}

/** Compose two parses where one produced only fragments, e.g. EPUB metadata over filename hints. */
export function withFilenameFallback(
  parsed: ParsedMetadata,
  fallback: { title: string | null; author: string | null },
): ParsedMetadata {
  const tookTitle = !parsed.title && fallback.title !== null;
  const tookAuthor = parsed.contributors.length === 0 && fallback.author !== null;
  if (!tookTitle && !tookAuthor) return parsed;
  return {
    ...parsed,
    title: parsed.title ?? fallback.title,
    contributors: tookAuthor
      ? [{ name: fallback.author as string, role: 'author' as const }]
      : parsed.contributors,
    source: tookTitle || tookAuthor ? 'filename' : parsed.source,
    // A filename-derived value is a guess even when the container itself parsed cleanly.
    confidence: Math.min(parsed.confidence, tookTitle || tookAuthor ? 0.4 : 1),
    warnings: tookTitle
      ? [...parsed.warnings, 'title taken from the filename; please confirm']
      : parsed.warnings,
  };
}
