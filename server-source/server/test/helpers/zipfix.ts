import { strToU8 } from 'fflate';
import { crc32 } from 'node:zlib';

/**
 * Minimal ZIP writer for test fixtures, stored (uncompressed) entries only.
 *
 * A real EPUB is required by spec to start with an uncompressed `mimetype` entry containing the
 * literal string `application/epub+zip`, and the sniffer leans on exactly that. A writer that
 * compressed everything — which is what a general-purpose zip library does by default — would
 * produce a "valid" EPUB that no real producer ever emits, and the test would pass while the
 * production path stayed broken.
 *
 * Building it here also lets a test ask for the malformed variants directly: entry order shuffled,
 * mimetype compressed, both.
 */

export interface ZipEntry {
  name: string;
  data: Uint8Array;
}

/** A fixed, valid DOS timestamp. The value is irrelevant to every reader; only its shape is not. */
const DOS_TIME = (12 << 11) | (0 << 5) | 0;
const DOS_DATE = (46 << 9) | (1 << 5) | 1; // 2026-01-01, encoded as DOS counts it.

const u16 = (n: number) => [n & 0xff, (n >> 8) & 0xff];
const u32 = (n: number) => [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, (n >>> 24) & 0xff];

export function buildZip(entries: ZipEntry[], options: { shuffleMimetypeFirst?: boolean } = {}): Uint8Array {
  const local: number[] = [];
  const central: number[] = [];

  const ordered = options.shuffleMimetypeFirst
    ? [...entries].filter((e) => e.name !== 'mimetype').concat(entries.filter((e) => e.name === 'mimetype'))
    : entries;

  for (const entry of ordered) {
    const nameBytes = strToU8(entry.name);
    const crc = crc32(Buffer.from(entry.data));
    const offset = local.length;

    local.push(
      ...u32(0x0403_4b50),
      ...u16(20),
      ...u16(0),
      ...u16(0), // stored: no compression, so the mimetype bytes stay literal
      ...u16(DOS_TIME),
      ...u16(DOS_DATE),
      ...u32(crc),
      ...u32(entry.data.length),
      ...u32(entry.data.length),
      ...u16(nameBytes.length),
      ...u16(0),
      ...nameBytes,
      ...entry.data,
    );

    central.push(
      ...u32(0x0201_4b50),
      ...u16(20),
      ...u16(20),
      ...u16(0),
      ...u16(0),
      ...u16(DOS_TIME),
      ...u16(DOS_DATE),
      ...u32(crc),
      ...u32(entry.data.length),
      ...u32(entry.data.length),
      ...u16(nameBytes.length),
      ...u16(0),
      ...u16(0),
      ...u16(0),
      ...u16(0),
      ...u32(0),
      ...u32(offset),
      ...nameBytes,
    );
  }

  const end: number[] = [
    ...u32(0x0605_4b50),
    ...u16(0),
    ...u16(0),
    ...u16(ordered.length),
    ...u16(ordered.length),
    ...u32(central.length),
    ...u32(local.length),
    ...u16(0),
  ];

  return Uint8Array.from([...local, ...central, ...end]);
}

export const asEntries = (files: Record<string, string>): ZipEntry[] =>
  Object.entries(files).map(([name, text]) => ({ name, data: strToU8(text) }));
