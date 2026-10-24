import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { strToU8 } from 'fflate';
import { asEntries, buildZip } from './helpers/zipfix.ts';
import { formatFromExtension, sniff } from '../src/metadata/sniff.ts';
import { parseEpub, resolveHref } from '../src/metadata/epub.ts';
import { detectCharset, txtStats } from '../src/metadata/txt.ts';
import { openZipReader, sortedEntryNames, ZipError } from '../src/metadata/zip.ts';

/**
 * Fixture content is written for these tests; nothing is quoted from a real book.
 */

let dir: string;

beforeAll(() => {
  dir = mkdtempSync(join(tmpdir(), 'ebookd-meta-'));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
});

async function write(name: string, data: Uint8Array): Promise<string> {
  const path = join(dir, name);
  writeFileSync(path, data);
  return path;
}

const CONTAINER = `<?xml version="1.0" encoding="UTF-8"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`;

function opf(
  overrides: {
    title?: string | null;
    extraMeta?: string;
    creators?: string;
  } = {},
): string {
  const title = overrides.title === null ? '' : `<dc:title>${overrides.title ?? '山海之间'}</dc:title>`;
  return `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0" unique-identifier="uid">
  <metadata xmlns:opf="http://www.idpf.org/2007/opf">
    ${title}
    <dc:identifier id="uid">urn:isbn:9781234567897</dc:identifier>
    ${overrides.creators ?? '<dc:creator opf:role="aut" opf:file-as="林, 某">林某</dc:creator>'}
    <dc:language>zh-CN</dc:language>
    <dc:publisher>测试出版社</dc:publisher>
    <dc:date opf:event="publication">2021-05-01</dc:date>
    <dc:subject>科幻</dc:subject>
    <dc:subject>长篇</dc:subject>
    <dc:description>一段为测试而写的简介。</dc:description>
    ${overrides.extraMeta ?? '<meta property="belongs-to-collection" id="col1">山海系列</meta><meta property="group-position" refines="#col1">3</meta>'}
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
    <item id="cover-img" href="images/cover.png" media-type="image/png" properties="cover-image"/>
    <item id="ch1" href="text/chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/chapter2.xhtml" media-type="application/xhtml+xml"/>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2" linear="no"/>
  </spine>
</package>`;
}

const XHTML = (n: string) => `<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${n}</title></head>
<body><h1>${n}</h1><p>为测试编写的段落内容，编号 ${n}。</p></body></html>`;

/** A 1x1 PNG, generated rather than embedded from anywhere. */
const PNG_1PX = Uint8Array.from(
  Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
    'base64',
  ),
);

function epubEntries(extra: Record<string, string> = {}): ReturnType<typeof asEntries> {
  return asEntries({
    mimetype: 'application/epub+zip',
    'META-INF/container.xml': CONTAINER,
    'OEBPS/content.opf': opf(),
    'OEBPS/text/chapter1.xhtml': XHTML('第一章'),
    'OEBPS/text/chapter2.xhtml': XHTML('第二章'),
    'OEBPS/nav.xhtml': XHTML('目录'),
    'OEBPS/images/cover.png': '',
    ...extra,
  });
}

describe('sniff', () => {
  it('recognises a spec-shaped EPUB by its stored mimetype', () => {
    const result = sniff(buildZip(epubEntries()) as unknown as Uint8Array, 'book.epub');
    expect(result.format).toBe('epub');
    expect(result.extensionAgreed).toBe(true);
  });

  it('calls an EPUB an EPUB even when the extension lies', () => {
    const zip = buildZip(epubEntries()) as unknown as Uint8Array;
    expect(sniff(zip, 'notes.txt').format).toBe('epub');
    expect(sniff(zip, 'notes.txt').extensionAgreed).toBe(false);
    expect(sniff(zip, 'no-extension').format).toBe('epub');
  });

  it('falls back to the mimetype search when the entries are written out of order', () => {
    const zip = buildZip(epubEntries(), { shuffleMimetypeFirst: true }) as unknown as Uint8Array;
    // META-INF/ is the secondary signal and is only reachable via the central directory, so the
    // head window may not contain it; either answer must be a zip family, never 'txt'.
    expect(['epub', 'unknown', 'cbz']).toContain(sniff(zip, 'x.epub').format);
  });

  it('recognises PDF, PalmDB and plain text', () => {
    expect(sniff(strToU8('%PDF-1.7\n%\xE2\xE3\xCF\xD3\n'), 'a.pdf').format).toBe('pdf');
    const palm = new Uint8Array(256);
    palm.set(strToU8('MyBook'), 0);
    palm.set(strToU8('BOOK'), 56);
    palm.set(strToU8('MOBI'), 60);
    expect(sniff(palm, 'a.mobi').format).toBe('mobi');
    expect(sniff(strToU8('夜色如墨。\n\n第二行。\n'), 'a.txt').format).toBe('txt');
    expect(sniff(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]), 'a.jpg').format).toBe('unknown');
  });

  it('maps extensions only where an extension can be trusted at all', () => {
    expect(formatFromExtension('a.EPUB')).toBe('epub');
    expect(formatFromExtension('noext')).toBe(null);
    expect(formatFromExtension('a.azw3')).toBe('azw3');
  });
});

describe('zip reader', () => {
  let path: string;
  beforeAll(async () => {
    path = await write('read.zip', buildZip(epubEntries()) as unknown as Uint8Array);
  });

  it('reads an entry by exact name and by folded (case-insensitive) name', async () => {
    const reader = await openZipReader(path);
    try {
      const exact = await reader.readText('OEBPS/content.opf');
      expect(exact).toContain('<dc:title>');
      expect(await reader.readText('oebps/CONTENT.OPF')).toBe(exact);
    } finally {
      reader.close();
    }
  });

  it('bounds a read by declared size before decompressing anything', async () => {
    const reader = await openZipReader(path);
    try {
      await expect(reader.read('OEBPS/content.opf', 8)).rejects.toThrow(/bytes/);
    } finally {
      reader.close();
    }
  });

  it('drops absolute and traversal entry names instead of exposing them', async () => {
    const hostile = await write(
      'hostile.zip',
      buildZip(
        asEntries({ 'safe.txt': 'ok' }).concat([
          { name: '/etc/passwd', data: strToU8('no') },
          { name: '../../escape.txt', data: strToU8('no') },
        ]),
      ) as unknown as Uint8Array,
    );
    const reader = await openZipReader(hostile);
    try {
      expect([...reader.entries.keys()]).toEqual(['safe.txt']);
    } finally {
      reader.close();
    }
  });
});

describe('comic page ordering', () => {
  it('sorts naturally so the cover we pick is page 1 and not page 10', async () => {
    const path = await write(
      'comic.cbz',
      buildZip(asEntries({ 'p10.png': '', 'p2.png': '', 'p1.png': '', 'p20.png': '', 'p3.png': '' })) as unknown as Uint8Array,
    );
    const reader = await openZipReader(path);
    try {
      expect(sortedEntryNames(reader)).toEqual(['p1.png', 'p2.png', 'p3.png', 'p10.png', 'p20.png']);
    } finally {
      reader.close();
    }
  });
});

describe('zip reader failures', () => {
  it('refuses a missing entry with a typed error rather than undefined', async () => {
    const path = await write('plain.zip', buildZip(epubEntries()) as unknown as Uint8Array);
    const reader = await openZipReader(path);
    try {
      const error = await reader.read('nope/does-not-exist').then(() => null, (e: unknown) => e);
      expect(error).toBeInstanceOf(ZipError);
      expect((error as ZipError).reason).toBe('missing');
    } finally {
      reader.close();
    }
  });

  it('refuses to open something that is not a zip at all', async () => {
    const path = await write('notzip.bin', strToU8('just some text, no archive here'));
    await expect(openZipReader(path)).rejects.toBeInstanceOf(ZipError);
  });
});

describe('EPUB metadata', () => {
  let path: string;

  beforeAll(async () => {
    const entries = epubEntries();
    // Replace the empty placeholder with real PNG bytes so the cover path is exercised.
    const withCover = entries.map((e) => (e.name === 'OEBPS/images/cover.png' ? { ...e, data: PNG_1PX } : e));
    path = await write('real.epub', buildZip(withCover) as unknown as Uint8Array);
  });

  it('reads title, authors, publisher, language, subjects and identifier', async () => {
    const meta = await parseEpub(path);
    expect(meta.title).toBe('山海之间');
    expect(meta.contributors).toEqual([
      { name: '林某', role: 'author', sortName: '林, 某' },
    ]);
    expect(meta.publisher).toBe('测试出版社');
    expect(meta.language).toBe('zh-CN');
    expect(meta.tags).toEqual(['科幻', '长篇']);
    expect(meta.isbn).toBe('9781234567897');
    expect(meta.publishedYear).toBe(2021);
    expect(meta.description).toContain('为测试而写的简介');
    expect(meta.source).toBe('epub');
    expect(meta.confidence).toBeGreaterThan(0.9);
    expect(meta.drm).toBe(false);
  });

  it('resolves the series and its position from either dialect', async () => {
    const epub3 = await parseEpub(path);
    expect(epub3.series).toBe('山海系列');
    expect(epub3.seriesIndex).toBe(3);

    // Built by substitution rather than by regex-patching the default OPF: a .replace() whose
    // pattern has drifted silently produces the *other* dialect and the assertion then proves
    // nothing about the one it claims to.
    const calibreOpf = opf({
      extraMeta:
        '<meta name="calibre:series" content="旧系列"/><meta name="calibre:series_index" content="1.5"/>',
    });
    expect(calibreOpf).toContain('calibre:series');
    expect(calibreOpf).not.toContain('belongs-to-collection');

    const calibrePath = await write(
      'calibre.epub',
      buildZip(
        epubEntries().map((e) =>
          e.name === 'OEBPS/content.opf' ? { ...e, data: strToU8(calibreOpf) } : e,
        ),
      ) as unknown as Uint8Array,
    );
    const calibre = await parseEpub(calibrePath);
    expect(calibre.series).toBe('旧系列');
    expect(calibre.seriesIndex).toBe(1.5);
  });

  it('does not mispair a position belonging to a different collection', async () => {
    const twoCollections = opf({
      extraMeta:
        '<meta property="belongs-to-collection" id="c1">甲系列</meta>' +
        '<meta property="belongs-to-collection" id="c2">乙系列</meta>' +
        '<meta property="group-position" refines="#c2">7</meta>',
    });
    const multiPath = await write(
      'multi.epub',
      buildZip(
        epubEntries().map((e) =>
          e.name === 'OEBPS/content.opf' ? { ...e, data: strToU8(twoCollections) } : e,
        ),
      ) as unknown as Uint8Array,
    );
    const multi = await parseEpub(multiPath);
    // The first collection owns no position, so claiming 7 for it would put the book on the
    // wrong shelf. Reporting null is the honest answer.
    expect(multi.series).toBe('甲系列');
    expect(multi.seriesIndex).toBeNull();
  });

  it('extracts the declared cover image', async () => {
    const meta = await parseEpub(path);
    expect(meta.cover?.mediaType).toBe('image/png');
    expect(meta.cover?.bytes.length).toBe(PNG_1PX.length);
  });

  it('lists only the linear spine documents', async () => {
    const meta = await parseEpub(path);
    expect(meta.spine).toEqual(['OEBPS/text/chapter1.xhtml']);
  });

  it('flags a container with no title rather than inventing a confident one', async () => {
    const titleless = await write(
      'notitle.epub',
      buildZip(
        epubEntries().map((e) =>
          e.name === 'OEBPS/content.opf' ? { ...e, data: strToU8(opf({ title: null })) } : e,
        ),
      ) as unknown as Uint8Array,
    );
    const meta = await parseEpub(titleless);
    expect(meta.title).toBeNull();
    expect(meta.warnings.join(' ')).toContain('dc:title');
    expect(meta.source).toBe('filename');
  });

  it('survives an OPF whose entry casing does not match the archive', async () => {
    const messy = await write(
      'messy.epub',
      buildZip(
        epubEntries()
          .filter((e) => e.name !== 'OEBPS/content.opf')
          .concat({ name: 'OEBPS/Content.OPF', data: strToU8(opf()) }),
      ) as unknown as Uint8Array,
    );
    // container.xml points at the lowercase path; the folded lookup is what makes that work.
    const meta = await parseEpub(messy);
    expect(meta.title).toBe('山海之间');
  });

  it('rejects a zip that contains no OPF at all', async () => {
    const fake = await write(
      'fake.epub',
      buildZip(asEntries({ mimetype: 'application/epub+zip', 'readme.txt': 'no opf here' })) as unknown as Uint8Array,
    );
    await expect(parseEpub(fake)).rejects.toBeInstanceOf(ZipError);
  });
});

describe('href resolution', () => {
  it('applies OPF-relative paths, percent escapes and parent traversal', () => {
    expect(resolveHref('OEBPS/', 'text/chap%201.xhtml')).toBe('OEBPS/text/chap 1.xhtml');
    expect(resolveHref('OEBPS/', '../out.xhtml')).toBe('out.xhtml');
    expect(resolveHref('', 'index.xhtml')).toBe('index.xhtml');
    expect(resolveHref('OEBPS/', 'nav.xhtml#frag')).toBe('OEBPS/nav.xhtml');
  });
});

describe('TXT charset detection', () => {
  const CN = '夜色如墨，远处传来汽笛声。这是一段为测试编写的中文文本。';

  it('trusts a BOM over any heuristic', () => {
    const utf16 = new Uint8Array(Buffer.from(`${CN}\n更多文字`.repeat(4), 'utf16le'));
    const withBom = Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from(utf16)]);
    const result = detectCharset(new Uint8Array(withBom));
    expect(result.charset).toBe('UTF-16LE');
    expect(result.text).toContain('夜色如墨');
  });

  it('accepts clean UTF-8 immediately', () => {
    const result = detectCharset(strToU8((CN + '\n\n').repeat(20)));
    expect(result.charset).toBe('UTF-8');
    expect(result.confidence).toBeGreaterThan(0.9);
  });

  it('keeps a UTF-8 file whose sample boundary cuts a character', () => {
    // 山 is E5 B1 B1, so 21 845 of them fill 65 535 bytes and the 64 KiB sample ends one byte into
    // the next character. The strict test used to read that as "not UTF-8" and the trial decode
    // answered GB18030, which decodes any byte sequence into something — every Chinese novel over
    // 64 KB, stored as 灞遍浘瀹.
    const text = '山'.repeat(21_846 + 20_000);
    const bytes = strToU8(text);
    expect(bytes.length).toBeGreaterThan(64 * 1024);
    expect(() => new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(0, 64 * 1024))).toThrow();

    const result = detectCharset(bytes);
    expect(result.charset).toBe('UTF-8');
    expect(result.confidence).toBeGreaterThan(0.9);
    expect(result.text.startsWith('山山山')).toBe(true);
    expect(result.text).not.toContain('灞');
  });

  it('recovers GB18030 text that is definitively not valid UTF-8', () => {
    // c4 e3 ba c3 is 你好 in GBK/GB18030 and is not a valid UTF-8 sequence, which is exactly the
    // case the detector exists for: a wrong codepage still decodes to *something*, so the only
    // safe assertions are "not UTF-8" and "no replacement characters".
    const pair = Uint8Array.from([0xc4, 0xe3, 0xba, 0xc3]);
    const gbk = new Uint8Array(pair.length * 60);
    for (let i = 0; i < 60; i += 1) gbk.set(pair, i * pair.length);

    expect(() => new TextDecoder('utf-8', { fatal: true }).decode(gbk)).toThrow();
    const result = detectCharset(gbk);
    expect(result.charset).not.toBe('UTF-8');
    // U+FFFD is what a failed decode leaves behind; a plausible-looking wrong guess would still
    // be mojibake, so the assertion is on the replacement character, not on a specific codepage.
    expect(result.text).not.toContain(String.fromCharCode(0xfffd));
    expect(result.text).toContain('你好');
  });

  it('says so when it is guessing', () => {
    expect(detectCharset(new Uint8Array([0x80, 0x81, 0x82, 0x83])).confidence).toBeLessThan(0.8);
  });
});

describe('TXT statistics', () => {
  it('counts CJK characters as words rather than splitting on spaces', () => {
    const stats = txtStats('山海之间。\n\n第二段，也很短。\n\n\n\n第三段。');
    expect(stats.charCount).toBeGreaterThan(0);
    expect(stats.wordCount).toBeGreaterThan(10);
    expect(stats.lineCount).toBe(7);
    expect(stats.preview).toContain('第二段');
  });

  it('does not report an empty document as a paragraph of whitespace', () => {
    const stats = txtStats('   \n\n   \n');
    expect(stats.charCount).toBe(0);
    expect(stats.preview).toBe('');
  });
});
