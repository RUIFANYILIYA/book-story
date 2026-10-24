import { randomBytes } from 'node:crypto';

/**
 * Sortable identifiers.
 *
 * `added_at` ordering and cursor pagination both want ids that sort by creation time, which
 * plain v4 UUIDs do not; SQLite gives us no autoincrement sequence for TEXT keys either. So:
 * 48-bit big-endian milliseconds + 80 random bits, Crockford base32, 26 chars — i.e. ULID,
 * implemented here rather than pulled in, because the alphabet and the padding are the entire
 * specification and a dependency would be more code than it replaces.
 */
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const TIME_CHARS = 10; // 48 bits / 5 bits per char
const RANDOM_CHARS = 16; // 80 bits / 5 bits per char

function encode(value: bigint, chars: number): string {
  let out = '';
  let v = value;
  for (let i = 0; i < chars; i += 1) {
    out = ALPHABET[Number(v & 31n)] + out;
    v >>= 5n;
  }
  if (v > 0n) throw new Error('id overflow: value does not fit the requested width');
  return out;
}

function decodeTime(id: string): bigint {
  let v = 0n;
  for (const c of id.slice(0, TIME_CHARS)) {
    const index = ALPHABET.indexOf(c.toUpperCase());
    if (index < 0) throw new Error(`not an ebookd id: ${id}`);
    v = (v << 5n) | BigInt(index);
  }
  return v;
}

export function newId(now: number = Date.now()): string {
  if (!Number.isInteger(now) || now < 0 || now > 2 ** 48 - 1) {
    throw new Error(`timestamp out of range for an id: ${now}`);
  }
  return encode(BigInt(now), TIME_CHARS) + encode(BigInt(`0x${randomBytes(10).toString('hex')}`), RANDOM_CHARS);
}

/** Milliseconds since epoch encoded in an id, for log correlation and idempotency checks. */
export function timestampOfId(id: string): number {
  if (id.length !== TIME_CHARS + RANDOM_CHARS) throw new Error(`not an ebookd id: ${id}`);
  return Number(decodeTime(id));
}

/**
 * True when `candidate` is a well-formed id. Callers that take a client-supplied id (the app
 * generates bookmark uuids offline) must reject garbage at the boundary rather than at the
 * first `slice()` that happens to touch it.
 */
export function isId(candidate: unknown): candidate is string {
  return (
    typeof candidate === 'string' &&
    candidate.length === TIME_CHARS + RANDOM_CHARS &&
    /^[0-9A-HJKMNP-TV-Z]{26}$/i.test(candidate)
  );
}
