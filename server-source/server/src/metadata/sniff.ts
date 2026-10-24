/**
 * Format identification by content, not by extension.
 *
 * A self-hosted library is full of files whose names lie: `.txt` holding UTF-16 XML, an EPUB
 * renamed `.zip`, a Kindle file called `book(2).mobi`. The extension is kept only as a tiebreak
 * and as the download filename, so the parser must be able to overrule it.
 *
 * Everything is read from a bounded head/tail window. A 900 MB CBZ must not be buffered to
 * discover what it is, and a ZIP's real end-of-central-directory sits at the *end*, so the tail
 * is scanned too.
 */

export type Format =
  | 'epub'
  | 'txt'
  | 'pdf'
  | 'mobi'
  | 'azw'
  | 'azw3'
  | 'kfx'
  | 'fb2'
  | 'cbz'
  | 'docx'
  | 'doc'
  | 'htmlz'
  | 'unknown';

export interface SniffResult {
  format: Format;
  mediaType: string;
  /** Magic bytes agreed with the extension the uploader claimed. */
  extensionAgreed: boolean;
  /** A DRM container was detected; metadata extraction will be limited. */
  drm: boolean;
}

const HEAD_BYTES = 4096;

const EXT_TO_FORMAT: Record<string, Format> = {
  epub: 'epub',
  txt: 'txt',
  pdf: 'pdf',
  mobi: 'mobi',
  prc: 'mobi',
  azw: 'azw',
  azw3: 'azw3',
  kfx: 'kfx',
  fb2: 'fb2',
  fb2z: 'fb2',
  cbz: 'cbz',
  docx: 'docx',
  doc: 'doc',
  htmlz: 'htmlz',
};

export function formatFromExtension(filename: string): Format | null {
  const dot = filename.lastIndexOf('.');
  if (dot < 0) return null;
  return EXT_TO_FORMAT[filename.slice(dot + 1).toLowerCase()] ?? null;
}

export const MEDIA_TYPES: Record<Format, string> = {
  epub: 'application/epub+zip',
  txt: 'text/plain',
  pdf: 'application/pdf',
  mobi: 'application/x-mobipocket-ebook',
  azw: 'application/vnd.amazon.ebook',
  azw3: 'application/vnd.amazon.ebook',
  kfx: 'application/vnd.amazon.kfx',
  fb2: 'application/x-fictionbook+xml',
  cbz: 'application/vnd.comicbook+zip',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  doc: 'application/msword',
  htmlz: 'application/zip',
  unknown: 'application/octet-stream',
};

const ascii = (head: Uint8Array, offset: number, text: string): boolean => {
  if (head.length < offset + text.length) return false;
  for (let i = 0; i < text.length; i += 1) {
    if (head[offset + i] !== text.charCodeAt(i)) return false;
  }
  return true;
};

/**
 * PalmDB: 32 name bytes, then a 0x0000 reserved word, then attributes/version at offsets 34..39,
 * and the creator/type four-char codes at 60 and 56 respectively.
 */
function palmCreator(head: Uint8Array): { type: string; creator: string } | null {
  if (head.length < 64) return null;
  // A valid PalmDB has offset 34 == 0 and the first 32 bytes printable-ish.
  if (head[34] !== 0x00 || head[35] !== 0x00) return null;
  const read = (at: number) =>
    String.fromCharCode(...[head[at]!, head[at + 1]!, head[at + 2]!, head[at + 3]!]);
  return { type: read(56), creator: read(60) };
}

function looksLikeText(head: Uint8Array): boolean {
  const limit = Math.min(head.length, 1024);
  if (limit === 0) return false;
  let control = 0;
  for (let i = 0; i < limit; i += 1) {
    const byte = head[i]!;
    const isTabNlCr = byte === 0x09 || byte === 0x0a || byte === 0x0d;
    if (byte < 0x20 && !isTabNlCr) control += 1;
  }
  if (control / limit >= 0.02) return false;
  // The control-byte test alone calls a JPEG "text": every byte is above 0x20, so none looks
  // suspicious. Text also has to *decode* to something, which is the same criterion the charset
  // detector uses — 0xff and 0xfe are never valid in any of the encodings we accept.
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(head.subarray(0, limit));
    return true;
  } catch {
    // Not UTF-8. Fall through to the legacy check below rather than treating it as text by default.
  }
  let highBytes = 0;
  for (let i = 0; i < limit; i += 1) if (head[i]! >= 0xf5) highBytes += 1;
  return highBytes / limit < 0.005;
}

export function sniff(head: Uint8Array, filename: string): SniffResult {
  const claimed = formatFromExtension(filename);
  const agree = (format: Format): boolean => claimed === null || claimed === format;

  const finish = (format: Format, drm = false, override = false): SniffResult => ({
    format,
    mediaType: MEDIA_TYPES[format],
    extensionAgreed: override || agree(format),
    drm,
  });

  // PDF first: "%PDF-" is unambiguous and cheap.
  if (ascii(head, 0, '%PDF-')) return finish('pdf');

  // PostScript and RTF arrive as plain text files far too often to ignore.
  if (ascii(head, 0, '%!PS')) return finish('unknown');
  if (ascii(head, 0, '{\\rtf')) return finish('unknown');

  const palm = palmCreator(head);
  if (palm) {
    // BOOKMOBI is the classic MOBI/AZW container; 'KFXC' marks KFX. Record 0's encryption field
    // is the low two bits at offset 128; non-zero means DRM, which usually also means the EXTH
    // metadata is unreadable. The file is still stored and listed either way.
    if (palm.type === 'BOOK' && palm.creator === 'MOBI') {
      const flags = (head[128]! << 24) | (head[129]! << 16) | (head[130]! << 8) | head[131]!;
      return finish('mobi', (flags & 0b11) !== 0);
    }
    if (palm.type === 'KFXC' || palm.creator === 'KFXC') return finish('kfx', true, true);
    if (palm.creator === 'TSR1' || palm.creator === 'TREZ') return finish('azw', false, true);
    return finish('unknown', false, true);
  }

  // ZIP family: local header 'PK\x03\x04' or an empty-archive end marker.
  if (ascii(head, 0, 'PK\u0003\u0004') || ascii(head, 0, 'PK\u0005\u0006')) {
    // EPUB carries its identity in the first entry's mimetype rather than in the container, so
    // the head window usually contains the literal bytes "mimetypeapplication/epub+zip".
    const window = new TextDecoder('latin1').decode(head.slice(0, HEAD_BYTES));
    if (window.includes('application/epub+zip')) return finish('epub');
    if (window.includes('word/') || window.includes('[Content_Types].xml')) return finish('docx');
    if (window.includes('OEBPS/') || window.includes('META-INF/')) return finish('epub', false, true);
    // CBZ, HTMLZ and FB2Z are all "a zip we cannot name from the head"; the caller resolves them
    // by opening the central directory, and the extension decides when that is ambiguous.
    if (claimed === 'cbz') return finish('cbz');
    if (claimed === 'htmlz') return finish('htmlz');
    // `.fb2z` is a compressed FB2, which the extension table already maps onto the fb2 format.
    if (claimed === 'fb2') return finish('fb2');
    return finish('unknown', false, true);
  }

  // TIFF magic: a comic page that some archive tools hand out bare rather than zipped.
  if (ascii(head, 0, 'MM\u0000*') || ascii(head, 0, 'II*\u0000')) {
    return finish('unknown', false, true);
  }

  // XML dialects by prolog content.
  const headText = new TextDecoder('latin1').decode(head.slice(0, 512));
  if (headText.includes('<FictionBook')) return finish('fb2');
  if (headText.includes('<html') || headText.includes('<!DOCTYPE html')) return finish('unknown', false, true);

  // LZFSE-compressed HTMLZ, used by iBooks.
  if (ascii(head, 0, 'bvx')) return finish('htmlz', false, true);

  if (looksLikeText(head)) {
    // UTF-16 and BOM-prefixed text are still text; the charset detector decides which.
    return finish('txt');
  }

  return finish(claimed ?? 'unknown', false, claimed !== null);
}

export const HEAD_WINDOW = HEAD_BYTES;
