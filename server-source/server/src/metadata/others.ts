import { asRecord, asRecords, attrString, parseXml, textList, textOf } from './util/xml.ts';
import { openZipReader, sortedEntryNames } from './zip.ts';
import { emptyMetadata, type ParsedMetadata } from './types.ts';

/**
 * FB2: a single XML document, so metadata and cover are both read from the same parse.
 *
 * FictionBook is the one format where the cover is *inside* the metadata as base64, which makes
 * extraction unusually reliable — and where `<sequence>` gives a real series with a number, which
 * EPUB only gets right half the time.
 */
export async function parseFb2(path: string): Promise<ParsedMetadata> {
  const reader = await openZipReader(path).catch(() => null);
  if (reader) {
    // .fb2z is a zipped FB2; take the single XML entry out and re-read it as text below.
    const inner = sortedEntryNames(reader).find((n) => n.toLowerCase().endsWith('.fb2'));
    const bytes = inner ? await reader.read(inner, 64 * 1024 * 1024) : null;
    reader.close();
    if (!bytes) throw new Error('fb2z archive contains no .fb2 entry');
    return fromXml(new TextDecoder('utf-8').decode(bytes));
  }
  const { readFile } = await import('node:fs/promises');
  return fromXml((await readFile(path)).toString('utf8'));
}

function fromXml(document: string): ParsedMetadata {
  const root = asRecord(parseXml(document));
  const fictionBook = asRecord(root.FictionBook);
  const description = asRecord(fictionBook.description);
  const info = asRecord(description['bibliographic-info']);
  // Bracket access, not a dot: `info.title-info` parses as subtraction and yielded NaN-as-a-key
  // rather than a compile error, because the record index type is unknown.
  const titleInfo = asRecord(info['title-info']);

  const authors = asRecords(titleInfo.author).flatMap((author) => {
    const nickname = textOf(asRecord(author.nickname));
    const first = textOf(asRecord(author.first_name));
    const middle = textOf(asRecord(author.middle_name));
    const last = textOf(asRecord(author.last_name));
    const assembled = [last, first, middle].filter(Boolean).join(' ');
    // Parenthesised, not chained: `a ?? b || c` is a syntax error in TypeScript, and the intent
    // here is "prefer the nickname, else build one from the name parts".
    const name = nickname ?? (assembled.length > 0 ? assembled : null);
    return name ? [{ name, role: 'author' as const, sortName: last }] : [];
  });

  const documentNode = fictionBook;
  const sequences = asRecords(titleInfo.sequence);
  const firstSequence = sequences[0];

  const cover = (() => {
    const href = attrString(asRecord(titleInfo.coverpage), 'href');
    if (!href) return null;
    const id = href.replace(/^#/, '');
    const binary = asRecords(documentNode['binary']).find((b) => attrString(b, 'id') === id);
    if (!binary) return null;
    const payload = (textOf(binary) ?? String(binary['#text'] ?? '')).replace(/\s+/gu, '');
    if (payload.length === 0) return null;
    return { bytes: Buffer.from(payload, 'base64'), mediaType: attrString(binary, 'format') ?? 'image/jpeg' };
  })();

  const genres = textList(asRecord(infoGenres(info)).genre);
  const hasTitle = textList(titleInfo['book-title']).length > 0;

  return {
    ...emptyMetadata('fb2', 'fb2'),
    title: textList(titleInfo['book-title'])[0] ?? null,
    subtitle: textList(titleInfo['wallpaper-title'])[0] ?? null,
    contributors: authors.filter((a): a is NonNullable<typeof a> => a !== null),
    series: firstSequence ? attrString(firstSequence, 'name') : null,
    seriesIndex: firstSequence ? numberOrNull(attrString(firstSequence, 'number')) : null,
    tags: genres,
    publisher: textList(info.publisher)[0] ?? null,
    publishedYear: yearFrom(textList(info.date)),
    language: textList(info.lang)[0]?.slice(0, 10) ?? null,
    description: firstAnnotation(description),
    cover,
    source: hasTitle ? 'fb2' : 'filename',
    confidence: hasTitle ? 0.85 : 0.2,
    warnings: hasTitle ? [] : ['FB2 has no book-title'],
  };
}

function infoGenres(info: Record<string, unknown>): Record<string, unknown> {
  return asRecord(info['info-types'] ?? info);
}

function firstAnnotation(description: Record<string, unknown>): string | null {
  for (const annotation of asRecords(description.annotation)) {
    const text = textList(annotation['p']).join(' ') || textOf(annotation) || '';
    if (text.trim().length > 0) return text.trim().slice(0, 4000);
  }
  return null;
}

function numberOrNull(value: string | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function yearFrom(values: string[]): number | null {
  for (const value of values) {
    const match = /\d{4}/u.exec(value);
    if (match) return Number(match[0]);
  }
  return null;
}

/**
 * CBZ: no metadata standard, only filenames and an optional `ComicInfo.xml` from Comix.
 *
 * The cover is chosen as the first image under natural ordering, because a comic's page files are
 * usually `001.jpg … 023.jpg` and a lexicographic sort would happily make page 10 the cover.
 */
export async function parseCbz(path: string): Promise<ParsedMetadata> {
  const reader = await openZipReader(path);
  try {
    const names = sortedEntryNames(reader);
    const images = names.filter((n) => /\.(png|jpe?g|webp|gif|bmp)$/iu.test(n));
    if (images.length === 0) {
      return {
        ...emptyMetadata('cbz', 'filename'),
        warnings: ['CBZ contains no images'],
        confidence: 0.1,
      };
    }
    const comicInfoIndex = names.findIndex((n) => n.toLowerCase().endsWith('comicinfo.xml'));
    let title: string | null = null;
    let series: string | null = null;
    let seriesIndex: number | null = null;
    let tags: string[] = [];
    let description: string | null = null;
    const contributors: ParsedMetadata['contributors'] = [];

    if (comicInfoIndex >= 0) {
      try {
        const parsed = asRecord(parseXml(await reader.readText(names[comicInfoIndex]!)));
        const info = asRecord(parsed.ComicInfo);
        title = textList(info.Title)[0] ?? null;
        series = textList(info.Series)[0] ?? null;
        seriesIndex = numberOrNull(textList(info.Count)[0] ?? textList(info.Number)[0] ?? null);
        tags = textList(info.Genre).flatMap((g) => g.split(',').map((s) => s.trim()).filter(Boolean));
        description = textList(info.Summary)[0] ?? null;
        const writer = textList(info.Writer)[0];
        const penciller = textList(info.Penciller)[0];
        if (writer) contributors.push({ name: writer, role: 'author' });
        if (penciller && penciller !== writer) contributors.push({ name: penciller, role: 'illustrator' });
      } catch {
        // ComicInfo.xml is optional and frequently malformed; a CBZ without it is still a CBZ.
      }
    }

    // Prefer an entry actually named cover; otherwise the first page in natural order.
    const coverName = images.find((n) => /cover/iu.test(n)) ?? images[0]!;
    const bytes = await reader.read(coverName, 25 * 1024 * 1024);

    return {
      ...emptyMetadata('cbz', title ? 'cbz' : 'filename'),
      title,
      series,
      seriesIndex,
      tags,
      description,
      contributors,
      pageCount: images.length,
      cover: { bytes, mediaType: mediaTypeFor(coverName) },
      confidence: title ? 0.75 : 0.3,
      warnings: title ? [] : ['no ComicInfo.xml; title must come from the filename'],
    };
  } finally {
    reader.close();
  }
}

function mediaTypeFor(name: string): string {
  const lower = name.toLowerCase();
  if (lower.endsWith('.png')) return 'image/png';
  if (lower.endsWith('.gif')) return 'image/gif';
  if (lower.endsWith('.webp')) return 'image/webp';
  if (lower.endsWith('.bmp')) return 'image/bmp';
  return 'image/jpeg';
}

/**
 * DOCX: `docProps/core.xml` is Dublin Core, so the same accessor code path as everything else.
 *
 * The trap here is a document produced from a blank template, where `dc:title` is literally
 * "Word 文件" or empty. Accepting that would fill a shelf with identical fake titles, so an empty
 * or boilerplate title falls back to the filename and is flagged.
 */
export async function parseDocx(path: string): Promise<ParsedMetadata> {
  const reader = await openZipReader(path);
  try {
    const core = asRecord(parseXml(await reader.readText('docProps/core.xml')));
    const meta = asRecord(core['cp:coreProperties']);
    const rawTitle = textList(meta['dc:title'])[0]?.trim() ?? null;
    const boilerplate = rawTitle === null || /^(microsoft )?(word|文档|word document)$/iu.test(rawTitle);
    const creators = textList(meta['dc:creator']);

    let appTitle: string | null = null;
    try {
      const app = asRecord(parseXml(await reader.readText('docProps/app.xml')));
      appTitle = textList(asRecord(app.Properties).TitlesOfParts)?.[0] ?? null;
    } catch {
      // app.xml is optional.
    }

    return {
      ...emptyMetadata('docx', boilerplate ? 'filename' : 'docx'),
      title: boilerplate ? null : rawTitle,
      contributors: creators.map((name) => ({ name, role: 'author' as const })),
      publisher: textList(meta['cp:lastModifiedBy'])[0] ?? null,
      description: appTitle,
      confidence: boilerplate ? 0.3 : 0.7,
      warnings: boilerplate ? ['docProps/core.xml has no usable dc:title'] : [],
    };
  } catch {
    return {
      ...emptyMetadata('docx', 'filename'),
      confidence: 0.2,
      warnings: ['docProps/core.xml unreadable'],
    };
  } finally {
    reader.close();
  }
}

/**
 * PDF metadata, best-effort, without a PDF renderer.
 *
 * Two encodings coexist in the wild: the legacy `/Info` dictionary and an XMP packet. A correct
 * implementation parses the xref table to find the trailer; that would mean writing a PDF parser,
 * and the value is low because most scanned or exported PDFs carry nothing useful in either place.
 * So: look for an XMP packet in the first and last megabyte, fall back to a bounded scan for
 * `/Title (…)`, and otherwise say so with low confidence rather than inventing a title.
 */
export async function parsePdf(path: string): Promise<ParsedMetadata> {
  const { open, stat } = await import('node:fs/promises');
  const { size } = await stat(path);
  const handle = await open(path, 'r');
  try {
    const window = 1024 * 1024;
    const head = Buffer.alloc(Math.min(window, size));
    await handle.read(head, 0, head.length, 0);
    const tail = Buffer.alloc(Math.min(window, size));
    if (size > 0) {
      await handle.read(tail, 0, tail.length, Math.max(0, size - tail.length));
    }
    const text = `${head.toString('latin1')}\n${tail.toString('latin1')}`;

    const xmp = /<x:xmpmeta[\s\S]*?<\/x:xmpmeta>/u.exec(text)?.[0];
    let title: string | null = null;
    let author: string | null = null;
    let description: string | null = null;
    let keywords: string[] = [];
    let producer: string | null = null;

    if (xmp) {
      const root = asRecord(parseXml(xmp));
      const rdf = asRecord(root['x:xmpmeta']);
      const bag = (node: unknown) => textList(node);
      for (const desc of asRecords(rdf['rdf:RDF'])) {
        for (const node of asRecords(desc['rdf:Description'])) {
          title ??= textList(node['dc:title'])[0] ?? null;
          author ??= textList(node['dc:creator'])[0] ?? null;
          description ??= textList(node['dc:description'])[0] ?? null;
          keywords = keywords.concat(bag(node['pdf:Keywords']).flatMap((k) => k.split(/[,;，、]/u).map((s) => s.trim()).filter(Boolean)));
          producer ??= textList(node['pdf:Producer'])[0] ?? null;
        }
      }
    }

    if (!title) {
      title = /\/Title\s*\(((?:[^()\\]|\\.)*)\)/u.exec(text)?.[1]?.replace(/\\([()\\])/gu, '$1').trim() ?? null;
      author ??= /\/Author\s*\(((?:[^()\\]|\\.)*)\)/u.exec(text)?.[1]?.replace(/\\([()\\])/gu, '$1').trim() ?? null;
    }

    const pageCount = /\/Count\s+(\d+)/u.exec(text)?.[1];
    const encrypted = /\/Encrypt\b/u.test(text);

    return {
      ...emptyMetadata('pdf', title ? 'pdf' : 'filename'),
      title: title && title.length > 1 ? title : null,
      contributors: author ? [{ name: author, role: 'author' }] : [],
      tags: [...new Set(keywords)],
      description,
      publisher: producer,
      pageCount: pageCount ? Number(pageCount) : null,
      drm: encrypted,
      source: title ? 'pdf' : 'filename',
      confidence: title ? 0.55 : 0.25,
      warnings: title
        ? []
        : ['PDF carries no readable XMP or /Info title; confirm the title from the filename'],
    };
  } finally {
    await handle.close();
  }
}
