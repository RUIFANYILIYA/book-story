import { parseEpub } from './epub.ts';
import { parseTxt } from './txt.ts';
import { parseCbz, parseDocx, parseFb2, parsePdf } from './others.ts';
import { emptyMetadata, type ParsedMetadata } from './types.ts';
import { HEAD_WINDOW, MEDIA_TYPES, sniff, type Format, type SniffResult } from './sniff.ts';
import { openZipReader } from './zip.ts';

export interface Identified {
  sniff: SniffResult;
  format: Format;
}

/** Read only the leading window; a 900 MB CBZ must not be buffered to find out what it is. */
export async function identify(path: string, filename: string): Promise<Identified> {
  const head = await readFileHead(path);
  const result = sniff(head, filename);
  // A ZIP whose entry list is the only thing that distinguishes it cannot be classified from the
  // head window alone: the central directory sits at the end of the file. sniff() deliberately
  // answers 'unknown' there, and this is where that promise is kept.
  const format = result.format === 'unknown' ? await refineZip(path, head) : result.format;
  if (format === result.format) return { sniff: result, format };
  return {
    sniff: { ...result, format, mediaType: MEDIA_TYPES[format] },
    format,
  };
}

/** Classify an unnamed ZIP by what is inside it. */
async function refineZip(path: string, head: Uint8Array): Promise<Format> {
  if (!asciiAt(head, 0, 'PK\u0003\u0004') && !asciiAt(head, 0, 'PK\u0005\u0006')) return 'unknown';
  let reader;
  try {
    reader = await openZipReader(path);
  } catch {
    return 'unknown';
  }
  try {
    const names = [...reader.entries.keys()];
    const lower = names.map((n) => n.toLowerCase());
    if (lower.some((n) => n.endsWith('.opf') || n === 'mimetype')) return 'epub';
    if (lower.some((n) => n.startsWith('word/'))) return 'docx';
    if (lower.some((n) => n.endsWith('.fb2'))) return 'fb2';
    const images = lower.filter((n) => /\.(png|jpe?g|webp|gif|bmp|avif)$/u.test(n));
    // A zip whose readable contents are all images is a comic book; anything else stays unknown
    // rather than being guessed into a format the app would then fail to open.
    if (images.length > 0 && images.length === lower.filter((n) => !n.endsWith('/')).length) return 'cbz';
    return 'unknown';
  } finally {
    reader.close();
  }
}

function asciiAt(bytes: Uint8Array, offset: number, text: string): boolean {
  if (bytes.length < offset + text.length) return false;
  for (let i = 0; i < text.length; i += 1) {
    if (bytes[offset + i] !== text.charCodeAt(i)) return false;
  }
  return true;
}

async function readFileHead(path: string): Promise<Uint8Array> {
  const { open, stat } = await import('node:fs/promises');
  const size = (await stat(path)).size;
  const length = Math.min(HEAD_WINDOW, size);
  const buffer = Buffer.alloc(length);
  if (length === 0) return new Uint8Array(0);
  const handle = await open(path, 'r');
  try {
    await handle.read(buffer, 0, length, 0);
  } finally {
    await handle.close();
  }
  return new Uint8Array(buffer);
}

/**
 * Dispatch on the *sniffed* format, never on the extension.
 *
 * Every parser is allowed to fail — a truncated archive, an OPF that is not XML, a ComicInfo with
 * a stray entity — and a failed parse must not lose the file. The library's job is to keep what it
 * was given and let a human fix the description, so the catch below degrades to a filename-derived
 * record with `needs_review` set rather than propagating.
 */
export async function parseFile(
  path: string,
  format: Format,
  filename: string,
): Promise<ParsedMetadata> {
  try {
    switch (format) {
      case 'epub':
        return await parseEpub(path);
      case 'txt':
        return await parseTxt(path);
      case 'fb2':
        return await parseFb2(path);
      case 'cbz':
        return await parseCbz(path);
      case 'docx':
        return await parseDocx(path);
      case 'pdf':
        return await parsePdf(path);
      case 'mobi':
      case 'azw':
      case 'azw3':
      case 'kfx':
        // A PalmDB/EXTH reader is deliberately not here yet. Its cover record index has to be
        // calibrated against real files, and guessing produces a wrong cover silently — worse
        // than no cover. Title from EXTH 503 would be safe, but that belongs with the rest.
        return {
          ...emptyMetadata(format, 'filename'),
          drm: format === 'kfx',
          confidence: 0.2,
          warnings: [`${format.toUpperCase()} metadata extraction is not implemented; title comes from the filename`],
        };
      default:
        return {
          ...emptyMetadata(format, 'filename'),
          confidence: 0.15,
          warnings: [`no parser for ${format}; stored as-is`],
        };
    }
  } catch (err) {
    return {
      ...emptyMetadata(format, 'filename'),
      confidence: 0.1,
      warnings: [
        `parser for ${format} failed: ${err instanceof Error ? err.message : String(err)}`,
        `title will be taken from the filename (${filename})`,
      ],
    };
  }
}

export { HEAD_WINDOW };
