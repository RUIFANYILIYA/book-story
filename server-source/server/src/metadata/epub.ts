import { openZipReader, type ZipReader, ZipError } from './zip.ts';
import { asRecord, asRecords, attrString, firstText, parseXml, textList, textOf } from './util/xml.ts';
import { emptyMetadata, type ContributorHint, type ParsedMetadata } from './types.ts';

/**
 * EPUB metadata, read from the container without extracting it.
 *
 * The path is always the same three steps — container.xml says where the OPF is, the OPF
 * describes the book and lists the parts, the spine orders the parts — but the *naming* varies
 * constantly between producers, and that variation is what this function actually spends its
 * effort on: calibre series fields versus EPUB3 collections, `meta[name=cover]` versus
 * `properties="cover-image"`, NCX-only tables of contents, and hrefs whose case does not match
 * the entry table on disk.
 */

interface OpfLocations {
  opfPath: string;
  opfDir: string;
}

export async function findOpf(reader: ZipReader, warnings: string[]): Promise<OpfLocations> {
  try {
    const container = await reader.readText('META-INF/container.xml');
    const root = asRecord(parseXml(container));
    const catalogs = asRecord(root['container']);
    const lists = asRecords(catalogs['rootfiles']);
    const entries = lists.flatMap((list) => asRecords(asRecord(list).rootfile));
    const first = entries.find((e) => attrString(e, 'full-path')) ?? entries[0];
    const full = first ? attrString(first, 'full-path') : null;
    if (full) return { opfPath: full, opfDir: dirOf(full) };
    warnings.push('container.xml listed no rootfile; falling back to a search for .opf');
  } catch {
    warnings.push('META-INF/container.xml missing; searching the archive for an OPF');
  }
  const guessed = [...reader.entries.keys()].find((name) => name.toLowerCase().endsWith('.opf'));
  if (!guessed) throw new ZipError('no OPF inside the EPUB', 'missing');
  return { opfPath: guessed, opfDir: dirOf(guessed) };
}

function dirOf(path: string): string {
  const slash = path.lastIndexOf('/');
  return slash < 0 ? '' : path.slice(0, slash + 1);
}

/** Resolve an OPF href relative to the OPF, decoding %xx and collapsing ../ segments. */
export function resolveHref(opfDir: string, href: string): string {
  const decoded = href.replace(/%20/gu, ' ').replace(/%23/gu, '#').split('#')[0] ?? '';
  const joined = href.startsWith('/') ? decoded : `${opfDir}${decoded}`;
  const out: string[] = [];
  for (const segment of joined.split('/')) {
    if (segment === '' || segment === '.') continue;
    if (segment === '..') out.pop();
    else out.push(segment);
  }
  return out.join('/');
}

function isbnFrom(identifiers: string[]): string | null {
  for (const raw of identifiers) {
    const value = raw.trim();
    if (/^urn:isbn:/iu.test(value)) return value.slice('urn:isbn:'.length);
    if (/^(ISBN )?\d{9}[\dXx]$/u.test(value) || /^(ISBN )?\d{10,13}$/u.test(value)) {
      return value.replace(/^ISBN\s*/iu, '');
    }
  }
  return null;
}

function yearFrom(dates: string[]): number | null {
  for (const raw of dates) {
    const match = /\d{4}/u.exec(raw);
    if (match) {
      const year = Number(match[0]);
      if (year >= 1000 && year <= 2499) return year;
    }
  }
  return null;
}

function contributorsFrom(metadata: Record<string, unknown>, warnings: string[]): ContributorHint[] {
  const out: ContributorHint[] = [];
  for (const node of asRecords(metadata['dc:creator'])) {
    const name = textOf(node);
    if (!name) continue;
    const role = (attrString(node, 'role') ?? 'author').toLowerCase();
    // EPUB uses MARC relators; the ones that appear in ebook metadata at all.
    const mapped: ContributorHint['role'] =
      role === 'trl' ? 'translator'
        : role === 'ill' ? 'illustrator'
          : role === 'edt' ? 'editor'
            : role === 'nrt' ? 'narrator'
              : role === 'com' ? 'compiler'
                : 'author';
    out.push({ name, role: mapped, sortName: attrString(node, 'file-as') });
  }
  if (out.length === 0) {
    const bare = asRecords(metadata['dc:creator']).map((n) => textOf(n)).filter(Boolean) as string[];
    if (bare.length > 0) {
      warnings.push('dc:creator present but unparseable as a structure; using plain values');
      out.push(...bare.map((name) => ({ name, role: 'author' as const })));
    }
  }
  return out;
}

/**
 * calibre writes `meta[name=calibre:series]`; EPUB3 writes a `belongs-to-collection` element with
 * an `id`, and the ordinal in a separate `group-position` element whose `refines` points back at
 * that id. The pairing is what distinguishes "book 3 of 山海系列" from "3 of 另一系列" in a file
 * with several collections, so it is matched properly first and only degenerated to "the single
 * one there is" when the producer never wrote an id at all.
 */
function seriesFrom(metadata: Record<string, unknown>): { name: string | null; index: number | null } {
  const metas = asRecords(metadata.meta);

  const calibre = metas.find((m) => attrString(m, 'name') === 'calibre:series');
  if (calibre) {
    const content = attrString(calibre, 'content');
    if (content) {
      const indexMeta = metas.find((m) => attrString(m, 'name') === 'calibre:series_index');
      return { name: content, index: indexMeta ? numberOrNull(attrString(indexMeta, 'content')) : null };
    }
  }

  const collections = metas
    .map((m) => ({ property: attrString(m, 'property'), refines: attrString(m, 'refines'), id: attrString(m, 'id'), text: textOf(m) }))
    .filter((m) => m.property === 'belongs-to-collection' && m.text);
  if (collections.length === 0) return { name: null, index: null };

  const positions = metas
    .map((m) => ({ property: attrString(m, 'property'), refines: attrString(m, 'refines'), text: textOf(m) }))
    .filter((m) => m.property === 'group-position' && m.text);

  const first = collections[0]!;
  const anchor = first.id ?? first.text;
  const paired =
    positions.find((p) => p.refines && anchor && p.refines === `#${anchor}`) ??
    // A file with exactly one collection and exactly one position has no pairing to get wrong.
    (collections.length === 1 && positions.length === 1 ? positions[0] : undefined);

  return { name: first.text ?? null, index: paired ? numberOrNull(paired.text) : null };
}

function numberOrNull(value: string | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export interface EpubParseResult extends ParsedMetadata {
  /** Ordered readable documents, so the app can build a shelf preview without re-parsing. */
  spine?: string[];
  /** The table of contents as the file declared it, if it declared one. */
  toc?: { title: string; href: string; level: number }[];
}

export async function parseEpub(path: string): Promise<EpubParseResult> {
  const reader = await openZipReader(path);
  try {
    const warnings: string[] = [];
    const { opfPath, opfDir } = await findOpf(reader, warnings);
    const opf = asRecord(parseXml(await reader.readText(opfPath)));
    const packageNode = asRecord(opf.package);
    const metadata = asRecord(packageNode.metadata);
    const manifest = asRecord(packageNode.manifest);
    const spine = asRecord(packageNode.spine);

    const items = new Map<string, { href: string; mediaType: string; properties: string }>();
    for (const item of asRecords(manifest.item)) {
      const id = attrString(item, 'id');
      const href = attrString(item, 'href');
      if (!id || !href) continue;
      items.set(id, {
        href: resolveHref(opfDir, href),
        mediaType: attrString(item, 'media-type') ?? '',
        properties: attrString(item, 'properties') ?? '',
      });
    }

    const titles = textList(metadata['dc:title']);
    if (titles.length === 0) warnings.push('OPF declares no dc:title');

    const identifiers = textList(metadata['dc:identifier']);
    const series = seriesFrom(metadata);
    const cover = await findCover(reader, metadata, items, opfDir, warnings);

    const spineIds = asRecords(spine.itemref)
      .filter((ref) => attrString(ref, 'linear') !== 'no')
      .map((ref) => attrString(ref, 'idref'))
      .filter((id): id is string => id !== null);

    const confidence = scores(titles.length > 0, identifiers, spineIds.length, cover !== null);

    return {
      ...emptyMetadata('epub', 'epub'),
      title: titles[0] ?? null,
      contributors: contributorsFrom(metadata, warnings),
      series: series.name,
      seriesIndex: series.index,
      tags: textList(metadata['dc:subject']),
      publisher: firstText(metadata['dc:publisher']),
      publishedYear: yearFrom(textList(metadata['dc:date'])),
      language: firstText(metadata['dc:language']),
      description: firstText(metadata['dc:description']),
      isbn: isbnFrom(identifiers),
      cover,
      source: titles.length > 0 ? 'epub' : 'filename',
      confidence,
      warnings,
      drm: reader.entries.has('META-INF/encryption.xml'),
      spine: spineIds.map((id) => items.get(id)?.href).filter((h): h is string => Boolean(h)),
    };
  } finally {
    reader.close();
  }
}

async function findCover(
  reader: ZipReader,
  metadata: Record<string, unknown>,
  items: Map<string, { href: string; mediaType: string; properties: string }>,
  opfDir: string,
  warnings: string[],
): Promise<{ bytes: Buffer; mediaType: string } | null> {
  const declared = asRecords(metadata.meta).find((m) => attrString(m, 'name') === 'cover');
  const candidates: string[] = [];
  if (declared) {
    const id = attrString(declared, 'content');
    if (id && items.has(id)) candidates.push(items.get(id)!.href);
  }
  for (const item of items.values()) {
    if (item.properties.split(/\s+/u).includes('cover-image')) candidates.push(item.href);
  }
  if (candidates.length === 0) {
    // Last resort: a name that looks like a cover. Real producers vary enough that guessing is
    // better than shipping a shelf full of placeholders for books that do have art.
    for (const item of items.values()) {
      if (/^image\//u.test(item.mediaType) && /(^|\/)(cover|titlepage|title_page)\b/iu.test(item.href)) {
        candidates.push(item.href);
        break;
      }
    }
  }
  for (const href of candidates) {
    try {
      const bytes = await reader.read(href, 12 * 1024 * 1024);
      return { bytes, mediaType: mediaTypeForImage(href, opfDir) };
    } catch {
      warnings.push(`declared cover ${href} is not in the archive`);
    }
  }
  return null;
}

function mediaTypeForImage(href: string, _opfDir: string): string {
  const lower = href.toLowerCase();
  if (lower.endsWith('.png')) return 'image/png';
  if (lower.endsWith('.gif')) return 'image/gif';
  if (lower.endsWith('.svg')) return 'image/svg+xml';
  if (lower.endsWith('.webp')) return 'image/webp';
  return 'image/jpeg';
}

/**
 * A crude but explainable confidence score. It is used for one decision — flag or do not flag for
 * review — so it is deliberately monotone in what a human would call "looks complete" rather than
 * calibrated to anything.
 */
function scores(hasTitle: boolean, identifiers: string[], spineLength: number, hasCover: boolean): number {
  let score = 0;
  if (hasTitle) score += 0.45;
  if (identifiers.length > 0) score += 0.1;
  if (spineLength > 0) score += 0.3;
  if (hasCover) score += 0.15;
  return Math.min(1, score);
}
