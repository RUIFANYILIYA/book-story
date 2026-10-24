import { createHash, randomBytes, randomUUID, scrypt, timingSafeEqual, type ScryptOptions } from 'node:crypto';

/**
 * `promisify(scrypt)` resolves to the 3-argument overload, because `scrypt`'s callback forms
 * differ only by the presence of `options` — so the parameters we actually need are unreachable
 * through it. Written by hand instead, and *not* replaced with `scryptSync`: one login blocking
 * the event loop for ~20 ms would stall every concurrent request and every streaming download.
 */
function scryptAsync(
  password: string,
  salt: Buffer,
  keyLength: number,
  options: ScryptOptions,
): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scrypt(password, salt, keyLength, options, (err, derived) => {
      if (err) reject(err);
      else resolve(derived);
    });
  });
}

/**
 * Password hashing with scrypt from `node:crypto`.
 *
 * argon2 would be the first choice on merit, but it is a native module and the whole image
 * stays buildable on a NAS only because the runtime tree has no `.node` in it. scrypt is the
 * same class of memory-hard function and is already in the process. bcrypt is not on the list
 * because it truncates at 72 bytes, which silently punishes exactly the long Chinese
 * passphrases this server is for.
 *
 * Parameters are recorded inside the hash string, so raising them later can only ever mean
 * "rehash on next successful login" rather than "everyone resets their password".
 */
const PARAMS = { N: 16384, r: 8, p: 1, keyLength: 32 } as const;
const SALT_BYTES = 16;
const PREFIX = 'scrypt';

export class PasswordError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PasswordError';
  }
}

/**
 * Modular-crypt-ish encoding: `scrypt$N$r$p$salt$hash`.
 *
 * A password longer than the sanity cap is rejected rather than silently truncated or hashed
 * expensively: at N=16384/r=8 the cost scales with nothing the attacker controls, but a
 * 1 MB "password" pasted into a form still has to be copied around by every layer in between.
 */
const MAX_PASSWORD_BYTES = 1024;

export async function hashPassword(password: string): Promise<string> {
  if (typeof password !== 'string' || password.length === 0) {
    throw new PasswordError('password must be a non-empty string');
  }
  if (Buffer.byteLength(password, 'utf8') > MAX_PASSWORD_BYTES) {
    throw new PasswordError(`password must be at most ${MAX_PASSWORD_BYTES} bytes`);
  }
  const salt = randomBytes(SALT_BYTES);
  const derived = (await scryptAsync(normalize(password), salt, PARAMS.keyLength, {
    N: PARAMS.N,
    r: PARAMS.r,
    p: PARAMS.p,
    maxmem: 256 * 1024 * 1024,
  })) as Buffer;
  return [PREFIX, PARAMS.N, PARAMS.r, PARAMS.p, salt.toString('base64url'), derived.toString('base64url')].join(
    '$',
  );
}

/**
 * NFKC-normalizes so a password pasted with compatibility characters does not split into
 * "works on the device I set it on". Deliberately applied at both ends, never stored.
 */
function normalize(password: string): string {
  return password.normalize('NFKC');
}

interface Parsed {
  N: number;
  r: number;
  p: number;
  salt: Buffer;
  hash: Buffer;
}

function parse(encoded: string): Parsed | null {
  const parts = encoded.split('$');
  if (parts.length !== 6 || parts[0] !== PREFIX) return null;
  const [, n, r, p, salt, hash] = parts as [string, string, string, string, string, string];
  const N = Number(n);
  const rr = Number(r);
  const pp = Number(p);
  if (!Number.isInteger(N) || !Number.isInteger(rr) || !Number.isInteger(pp)) return null;
  const saltBuf = Buffer.from(salt, 'base64url');
  const hashBuf = Buffer.from(hash, 'base64url');
  if (saltBuf.length === 0 || hashBuf.length === 0) return null;
  return { N, r: rr, p: pp, salt: saltBuf, hash: hashBuf };
}

export async function verifyPassword(password: string, encoded: string): Promise<boolean> {
  const parsed = parse(encoded);
  if (!parsed) return false;
  if (typeof password !== 'string' || password.length === 0) return false;
  const derived = (await scryptAsync(normalize(password), parsed.salt, parsed.hash.length, {
    N: parsed.N,
    r: parsed.r,
    p: parsed.p,
    maxmem: 256 * 1024 * 1024,
  })) as Buffer;
  // Constant-time only after the same-length check; a length mismatch is not secret.
  return derived.length === parsed.hash.length && timingSafeEqual(derived, parsed.hash);
}

/** True when a stored hash predates the current parameters and should be rewritten on login. */
export function needsRehash(encoded: string): boolean {
  const parsed = parse(encoded);
  if (!parsed) return true;
  return parsed.N !== PARAMS.N || parsed.r !== PARAMS.r || parsed.p !== PARAMS.p;
}

/**
 * Opaque refresh/API tokens. The value goes to the client; the server keeps only SHA-256, so a
 * database dump cannot mint credentials and a leak of the token store is not a leak of tokens.
 */
export function newOpaqueToken(): { value: string; hash: string } {
  const value = randomBytes(32).toString('base64url');
  return { value, hash: sha256Hex(value) };
}

export function sha256Hex(input: string): string {
  return createHash('sha256').update(input).digest('hex');
}

/** Stable hash of a user-supplied idempotency key, so the unique index cannot be probed for length. */
export function idempotencyHash(userId: string, key: string): string {
  return sha256Hex(`${userId}:${key.trim()}`);
}

export function newRequestId(): string {
  return randomUUID();
}
