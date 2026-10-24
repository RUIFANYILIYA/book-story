import { open, type Entry, type ZipFile } from 'yauzl';
import { basename } from 'node:path';

/**
 * Random-access reads from a ZIP container, for the formats that are ZIPs: EPUB, CBZ, DOCX.
 *
 * Two rules shape this file. Nothing is ever extracted to disk — a 300 MB comic book would turn
 * an import into an I/O storm, and the container is the thing we keep. And every read is capped,
 * because a metadata parse should touch a few kilobytes of an OPF, and a hostile or corrupt
 * archive can declare a 4-byte entry that inflates to 4 GB.
 *
 * yauzl opens by central directory, so listing entries never reads the compressed stream.
 */

/** Entries a metadata parse will ever plausibly need. */
export const TEXT_ENTRY_CAP = 8 * 1024 * 1024;
/** Covers and comic pages are legitimately large; still bounded. */
export const BINARY_ENTRY_CAP = 25 * 1024 * 1024;
/** A zip bomb can also be a *wide* zip: 20 000 entries is already past any real book. */
export const MAX_ENTRIES = 20_000;

export class ZipError extends Error {
  constructor(message: string, readonly reason: 'unopenable' | 'too-many-entries' | 'entry-too-large' | 'missing') {
    super(message);
    this.name = 'ZipError';
  }
}

export interface ZipEntryInfo {
  name: string;
  /** Uncompressed size as declared in the central directory. */
  size: number;
}

const openZip = (path: string): Promise<ZipFile> =>
  new Promise((resolve, reject) => {
    open(
      path,
      {
        lazyEntries: true,
        // autoClose defaults to true, which closes the handle the moment the last central-directory
        // entry has been listed — every later openReadStream then fails with "closed". Reading
        // entries is the whole point, so ownership of close stays with the caller.
        autoClose: false,
        validateEntrySizes: false,
        decodeStrings: false,
      },
      (err, zip) => {
        if (err || !zip) reject(new ZipError(`${basename(path)}: not a readable zip`, 'unopenable'));
        else resolve(zip);
      },
    );
  });

const readEntryBuffer = (zip: ZipFile, entry: Entry, cap: number): Promise<Buffer> =>
  new Promise((resolve, reject) => {
    if (entry.uncompressedSize > cap) {
      // Trust the declared size first: it is a cheap refusal before any decompression happens.
      reject(new ZipError(`entry ${entry.fileName} declares ${entry.uncompressedSize} bytes`, 'entry-too-large'));
      return;
    }
    zip.openReadStream(entry, (err, stream) => {
      if (err || !stream) {
        // Carry the underlying reason through; "cannot read entry" alone is unhelpful in a log.
        reject(
          new ZipError(
            `cannot read entry ${entry.fileName}: ${err?.message ?? 'no stream'}`,
            'unopenable',
          ),
        );
        return;
      }
      const chunks: Buffer[] = [];
      let total = 0;
      stream.on('data', (chunk: Buffer) => {
        total += chunk.length;
        // The declared size can lie; this is the check that actually bounds memory.
        if (total > cap) {
          stream.destroy();
          reject(new ZipError(`entry ${entry.fileName} inflated past ${cap} bytes`, 'entry-too-large'));
          return;
        }
        chunks.push(chunk);
      });
      stream.on('error', (e: Error) => reject(new ZipError(e.message, 'unopenable')));
      stream.on('end', () => resolve(Buffer.concat(chunks)));
    });
  });

export interface ZipReader {
  entries: Map<string, Entry>;
  /** Lower-cased name → real name. EPUB writers emit `META-INF/Container.xml` and friends. */
  folded: Map<string, string>;
  read(name: string, cap?: number): Promise<Buffer>;
  readText(name: string, cap?: number): Promise<string>;
  close(): void;
}

export async function openZipReader(path: string): Promise<ZipReader> {
  const zip = await openZip(path);
  const entries = new Map<string, Entry>();
  const folded = new Map<string, string>();

  await new Promise<void>((resolve, reject) => {
    zip.on('error', (err: Error) => reject(new ZipError(err.message, 'unopenable')));
    zip.on('end', () => resolve());
    zip.on('entry', (entry: Entry) => {
      const name = Buffer.from(entry.fileName).toString('utf8');
      entry.fileName = name;
      if (entries.size >= MAX_ENTRIES) {
        zip.close();
        reject(new ZipError(`more than ${MAX_ENTRIES} entries`, 'too-many-entries'));
        return;
      }
      if (!name.endsWith('/')) {
        // Absolute and traversal names are dropped rather than trusted to later call sites.
        if (!name.startsWith('/') && !name.includes('..')) {
          entries.set(name, entry);
          const lower = name.toLowerCase();
          if (!folded.has(lower)) folded.set(lower, name);
        }
      }
      // Must run for *every* entry, including the ones just rejected: yauzl only advances when
      // asked, so skipping this leaves the archive open and 'end' never fires.
      zip.readEntry();
    });
    zip.readEntry();
  });

  const lookup = (name: string): Entry | undefined =>
    entries.get(name) ?? entries.get(folded.get(name.toLowerCase()) ?? '');

  // async so a missing entry rejects rather than throwing synchronously out of the call: a
  // caller writing `.catch(...)` would otherwise never see it.
  const read = async (name: string, cap = TEXT_ENTRY_CAP): Promise<Buffer> => {
    const entry = lookup(name);
    if (!entry) throw new ZipError(`missing entry ${name}`, 'missing');
    return readEntryBuffer(zip, entry, cap);
  };

  return {
    entries,
    folded,
    read,
    async readText(name, cap) {
      return (await read(name, cap)).toString('utf8');
    },
    close() {
      zip.close();
    },
  };
}

/** Names sorted for display, used to pick a comic's first page deterministically. */
export function sortedEntryNames(reader: ZipReader): string[] {
  return [...reader.entries.keys()].sort((a, b) => naturalCompare(a, b));
}

/**
 * `page-2` before `page-10`. Plain lexicographic order puts page-10 second and makes the first
 * image of a CBZ — the cover we choose — wrong for any comic with more than nine pages.
 */
export function naturalCompare(a: string, b: string): number {
  const left = a.match(/^(\D*)(\d*)/u);
  const right = b.match(/^(\D*)(\d*)/u);
  const [la = '', lb = ''] = [left?.[1] ?? '', right?.[1] ?? ''];
  if (la !== lb) return la.localeCompare(lb);
  const [na = '', nb = ''] = [left?.[2] ?? '', right?.[2] ?? ''];
  if (na && nb) {
    const delta = Number(na) - Number(nb);
    if (delta !== 0) return delta;
  }
  return a.slice(la.length + na.length).localeCompare(b.slice(lb.length + nb.length));
}
