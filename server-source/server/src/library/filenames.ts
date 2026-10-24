/**
 * Filename heuristics, and the normalization rules every compared column depends on.
 *
 * These live together because they answer the same question — what is this book called, when the
 * file says nothing — and because the *comparison* half has to agree with the *extraction* half:
 * a duplicate guard that normalizes differently from the ingest path will never fire.
 */


const EXTENSIONS = new Set([
  'epub', 'txt', 'pdf', 'mobi', 'azw', 'azw3', 'kfx', 'fb2', 'fb2z', 'cbz', 'docx', 'doc', 'htmlz', 'zip',
]);

export interface FilenameHint {
  title: string | null;
  author: string | null;
  series: string | null;
  seriesIndex: number | null;
  year: number | null;
}

/**
 * Common shapes seen in Chinese and English ebook filenames.
 *
 * Deliberately ordered most-specific first, and every pattern requires a separator that a real
 * title rarely contains by accident — "第3章" inside a title is not a series number.
 */
const AUTHOR_DASH_TITLE = /^[\s._-]*([^\s][\p{L}\p{N} .·'’&-]{0,60}?)\s*[-–—_]\s*(.{1,120})$/u;
const TITLE_PAREN_AUTHOR = /^[\s._-]*(.{1,120}?)\s*[（(]\s*([\p{L}\p{N} .·'’&-]{1,40}?)\s*(?:著|编|绘)?\s*[）)]\s*$/u;
const AUTHOR_BEFORE_TITLE_CN = /^[\s._-]*作者[:：]\s*(.{1,40}?)\s*[  ]*[《「『“](.+?)[》」』”]/u;
const BRACKETED_TITLE = /[《「『“]([^》」』”]{1,150})[》」』”]/u;
/** `[群兴论坛]`, `（卷二）`, `(Vol. 3)` — a bracketed qualifier that is usually a series or a tag. */
const BRACKETED = /\s*[\[（(]([^\[\]（）()]{1,60}?)[\]）)]\s*/u;
/** `#12`, ` 3/20`, `-07` — an explicit sequence number, usually but not always a series index. */
const TRAILING_INDEX = /(?:#\s*(\d{1,4})|\s(\d{1,3})\s*\/\s*\d{1,3})$/u;

export function parseFilename(filename: string): FilenameHint {
  const base = stripExtension(filename);
  const hint: FilenameHint = { title: null, author: null, series: null, seriesIndex: null, year: null };

  const year = /\b(1[0-9]{3}|20[0-9]{2})\b/u.exec(base);
  if (year) hint.year = Number(year[1]);

  const indexMatch = TRAILING_INDEX.exec(base);
  if (indexMatch) hint.seriesIndex = Number(indexMatch[1] ?? indexMatch[2]);

  let working = base;
  const bracket = BRACKETED.exec(base);
  if (bracket) {
    const candidate = (bracket[1] ?? '').trim();
    // "(2020)" and "(1)" are dates and counters, not series names; keeping them invents a shelf
    // per publication year.
    if (candidate.length > 0 && !/^\d{1,4}$/u.test(candidate) && !/^\d{4}[a-z]?$/u.test(candidate)) {
      hint.series = candidate.replace(/^(?:卷|册|第)\s*/u, '').trim() || null;
    }
    working = working.replace(BRACKETED, ' ');
  }

  working = working
    .replace(/\b(1[0-9]{3}|20[0-9]{2})\b/u, ' ')
    .replace(TRAILING_INDEX, ' ')
    .replace(/[_+]+/gu, ' ')
    .trim();

  // The download-manager and forum suffixes that survive everywhere: "-副本", "精校版", "Vol.3".
  // The list has to cover the 版 forms explicitly: with only "精校" here, "标题 - 精校版" fell
  // straight through to the author-title split and the book was filed under 精校版.
  working = working
    .replace(
      /\s*[-–—]?\s*(?:副本|已解密|完整(?:版)?|高清|精校(?:版)?|校对(?:版)?|免转码|全集|txt下载|vol(?:ume)?\.?\s*\d+|chapter\s*\d+)\s*$/iu,
      '',
    )
    .trim();

  const bracketed = BRACKETED_TITLE.exec(working);
  if (bracketed) {
    hint.title = clean(bracketed[1] ?? '');
    const before = working
      .slice(0, bracketed.index)
      .replace(/[:：\s]+$/u, '')
      .replace(/^作者[:：]\s*/u, '');
    if (before.length > 0) hint.author = clean(before);
  }

  if (!hint.title) {
    const authorTitle = AUTHOR_DASH_TITLE.exec(working);
    const left = clean(authorTitle?.[1] ?? '');
    const right = clean(authorTitle?.[2] ?? '');
    // Language does not decide which side is the author - `周甲 - 长夜将至` and
    // `远山灯火 - 林某` are both natural Chinese filenames with opposite orders, so any rule
    // based on script or position gets half the library wrong. What does hold is that a personal
    // name is short and a title is long, so the shorter side becomes the author. The guard on
    // the left side's length still stops "Silver - The Alchemist - Deluxe" being split at all.
    if (authorTitle && left.length > 0 && left.length <= 30 && right.length > 0) {
      if (left.length <= right.length) {
        hint.author = left;
        hint.title = right;
      } else {
        hint.title = left;
        hint.author = right;
      }
    }
  }

  if (!hint.title) {
    const paren = TITLE_PAREN_AUTHOR.exec(working);
    if (paren && (paren[2] ?? '').length <= 40) {
      hint.title = clean(paren[1] ?? '');
      hint.author = clean(paren[2] ?? '');
    }
  }

  const cnAuthor = AUTHOR_BEFORE_TITLE_CN.exec(base);
  if (cnAuthor) {
    hint.author ??= clean(cnAuthor[1] ?? '');
    hint.title ??= clean(cnAuthor[2] ?? '');
  }

  if (!hint.title) hint.title = working.length > 0 ? clean(working) : null;
  // A title left as "(1)" or "x" after the noise is stripped is not a title; a human has to name it.
  if (hint.title !== null && hint.title.length < 2) hint.title = null;

  return hint;
}

export function stripExtension(filename: string): string {
  const slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
  const name = slash >= 0 ? filename.slice(slash + 1) : filename;
  const dot = name.lastIndexOf('.');
  if (dot <= 0) return name;
  return EXTENSIONS.has(name.slice(dot + 1).toLowerCase()) ? name.slice(0, dot) : name;
}

function clean(value: string): string {
  return value.replace(/\s{2,}/gu, ' ').replace(/^[\s.\-–—:：,，、]+|[\s.\-–—:：,，、]+$/gu, '').trim();
}

/**
 * The comparison form used by `title_norm`, `name_norm` and `search_text`.
 *
 * NFKC so full-width and half-width digits and punctuation collide, casefolded, then everything
 * that is not a letter or a number collapses to a single space. Without this, "三体", "三体 " and
 * "三  体" are three books to the dedupe index and one to a human.
 */
export function normalizeTitle(value: string): string {
  return value
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim()
    .replace(/\s{2,}/gu, ' ');
}

/** Sort key with the leading article folded away, so "The Hobbit" files under H. */
export function sortTitle(value: string): string {
  const normalized = normalizeTitle(value);
  return normalized.replace(/^(a|an|the)\s+/u, '') || normalized;
}

/** Rough "is this probably Chinese/Japanese?" for the word-count path. */
export function isCjkHeavy(value: string): boolean {
  const cjk = (value.match(/[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/gu) ?? []).length;
  return cjk > value.length * 0.2;
}
