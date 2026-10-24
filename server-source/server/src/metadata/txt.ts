import { emptyMetadata, type ParsedMetadata } from './types.ts';

/**
 * TXT charset detection and body statistics, with no third-party dependency.
 *
 * Node's bundled ICU is a full build in this runtime, so `new TextDecoder('gb18030')` works and
 * a strict UTF-8 pass can be used as a *test* rather than a decoder: if decoding the whole sample
 * as UTF-8 with `fatal: true` does not throw, the bytes really are UTF-8. That single fact removes
 * the need for a statistical detector in the common case and leaves scoring only for the genuinely
 * ambiguous legacy files.
 *
 * Getting this wrong is the number-one "the app shows mojibake" complaint, and the reason it is so
 * bad is that GB18030 and UTF-8 both decode each other's Chinese bytes into *something*. A wrong
 * guess is plausible-looking garbage, so the answer is persisted per file and only ever changed by
 * an explicit user action.
 */

const CANDIDATES = ['UTF-8', 'GB18030', 'Big5', 'Shift_JIS', 'EUC-KR', 'windows-1252'] as const;
const SAMPLE_BYTES = 64 * 1024;

export interface CharsetResult {
  charset: string;
  /** How sure we are; below the review threshold the operator is invited to correct it. */
  confidence: number;
  text: string;
}

function bomOf(head: Uint8Array): { charset: string; width: number } | null {
  if (head.length >= 3 && head[0] === 0xef && head[1] === 0xbb && head[2] === 0xbf) {
    return { charset: 'UTF-8', width: 3 };
  }
  if (head.length >= 2 && head[0] === 0xff && head[1] === 0xfe) return { charset: 'UTF-16LE', width: 2 };
  if (head.length >= 2 && head[0] === 0xfe && head[1] === 0xff) return { charset: 'UTF-16BE', width: 2 };
  return null;
}

function decodeWith(charset: string, bytes: Uint8Array, fatal: boolean): string | null {
  try {
    return new TextDecoder(charset, { fatal }).decode(bytes);
  } catch {
    return null;
  }
}

/**
 * A rough quality score for a candidate decoding. Chinese prose should be almost entirely CJK and
 * ASCII punctuation; a wrong codepage produces Cyrillic, Hangul or box-drawing characters at a rate
 * that is easy to spot statistically.
 */
function scoreText(text: string): number {
  const sample = text.slice(0, 4000);
  if (sample.length === 0) return 0;
  let cjk = 0;
  let latin = 0;
  let replacement = 0;
  let control = 0;
  for (const char of sample) {
    const cp = char.codePointAt(0)!;
    if (cp === 0xfffd) replacement += 1;
    else if (cp < 0x20 && cp !== 0x09 && cp !== 0x0a && cp !== 0x0d) control += 1;
    else if ((cp >= 0x4e00 && cp <= 0x9fff) || (cp >= 0x3400 && cp <= 0x4dbf)) cjk += 1;
    else if (cp >= 0x41 && cp <= 0x7a) latin += 1;
  }
  const total = sample.length;
  // Replacement characters and control bytes are close to fatal.
  if (replacement / total > 0.01 || control / total > 0.01) return -1;
  return (cjk + latin) / total;
}

/**
 * A strict UTF-8 decode of [sample], retrying with up to three bytes dropped from the end when the
 * sample is a truncation of a longer file.
 *
 * Without the backoff the strict test fails on any UTF-8 file whose sample boundary lands inside a
 * multi-byte character — every Chinese novel over 64 KB — and the trial decode below answers
 * GB18030, which decodes any byte sequence into something. That is a whole book of mojibake, and a
 * description written to the database in it.
 */
function strictUtf8(sample: Uint8Array, truncated: boolean): string | null {
  const whole = decodeWith('UTF-8', sample, true);
  if (whole !== null || !truncated) return whole;
  for (let drop = 1; drop <= 3; drop += 1) {
    const trimmed = decodeWith('UTF-8', sample.subarray(0, sample.length - drop), true);
    if (trimmed !== null) return trimmed;
  }
  return null;
}

export function detectCharset(bytes: Uint8Array): CharsetResult {
  const bom = bomOf(bytes);
  if (bom) {
    const text = decodeWith(bom.charset, bytes.subarray(bom.width), false) ?? '';
    return { charset: bom.charset, confidence: 0.99, text };
  }

  const sample = bytes.subarray(0, SAMPLE_BYTES);
  const strict = strictUtf8(sample, bytes.length > sample.length);
  if (strict !== null) {
    // Valid UTF-8 end to end. Pure-ASCII files are also valid UTF-8, which is the right answer.
    const nonAscii = [...sample].some((byte) => byte > 0x7f);
    return { charset: 'UTF-8', confidence: nonAscii ? 0.99 : 0.95, text: strict };
  }

  let best: { charset: string; text: string; score: number } | null = null;
  for (const charset of CANDIDATES) {
    if (charset === 'UTF-8') continue;
    const text = decodeWith(charset, sample, false);
    if (text === null) continue;
    const score = scoreText(text);
    if (score < 0) continue;
    if (!best || score > best.score) best = { charset, text, score };
  }

  if (best) {
    // Nothing was decodable without error, so confidence is capped: the UI offers a codepage switch.
    return { charset: best.charset, confidence: Math.min(0.75, 0.4 + best.score), text: best.text };
  }
  return { charset: 'UTF-8', confidence: 0.2, text: decodeWith('UTF-8', sample, false) ?? '' };
}

export interface TxtStats {
  charCount: number;
  wordCount: number;
  lineCount: number;
  /** First non-empty paragraphs, for a preview card. */
  preview: string;
}

/**
 * Statistics over decoded text. Whitespace handling is Unicode-aware because CJK text has no
 * spaces at all — splitting on whitespace would report a whole novel as two "words".
 */
export function txtStats(text: string): TxtStats {
  let charCount = 0;
  let lineCount = 0;
  for (const line of text.split('\n')) {
    lineCount += 1;
    charCount += line.replace(/\s/gu, '').length;
  }
  const cjk = (text.match(/[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/gu) ?? []).length;
  const latinWords = (text.match(/[A-Za-zÀ-ɏ][A-Za-z0-9'’-]*/gu) ?? []).length;

  const paragraphs = text
    .split(/\r?\n[ \t　]*\r?\n/u)
    .map((p) => p.replace(/\s+/gu, ' ').trim())
    .filter((p) => p.length > 0)
    .slice(0, 4);

  return {
    charCount,
    wordCount: cjk + latinWords,
    lineCount,
    preview: paragraphs.join(' ').slice(0, 400),
  };
}

export async function parseTxt(path: string): Promise<ParsedMetadata> {
  const { readFile } = await import('node:fs/promises');
  const bytes = await readFile(path);
  const detected = detectCharset(bytes);
  const stats = txtStats(detected.text);
  return {
    ...emptyMetadata('txt', 'txt'),
    // A .txt file carries no title. The filename heuristic fills it in during ingest, which is
    // why confidence starts low rather than at the parse quality of the text itself.
    title: null,
    charCount: stats.charCount,
    wordCount: stats.wordCount,
    description: stats.preview.length > 0 ? stats.preview : null,
    charset: detected.charset,
    source: 'txt',
    confidence: detected.confidence * 0.5,
    warnings:
      detected.confidence < 0.6
        ? [`charset guessed as ${detected.charset}; confirm it renders correctly`]
        : [],
  };
}
