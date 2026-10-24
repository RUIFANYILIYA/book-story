import { readFileSync } from 'node:fs';

const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as {
  version: string;
};

export const SERVER_VERSION = pkg.version;
export const API_VERSION = 'v1';
/** Bumped only on breaking wire changes; the app refuses to talk across a mismatch. */
export const PROTOCOL_VERSION = 1;
/** The app warns (non-blocking) when it is older than this. */
export const MIN_APP_VERSION_CODE = 1;

/**
 * Capability flags. The Android app hides UI behind these rather than probing endpoints and
 * handling 404s, so an unimplemented feature must be `false` here — never a crash on the client.
 * Adding a flag is additive; removing one means the server truly dropped the feature.
 */
export const FEATURES = {
  rangeDownloads: true,
  contentHashIntegrity: true,
  batchShelf: false,
  customCovers: false,
  progressSync: false,
  offlineManifest: false,
  serverThumbnails: false,
  opds: false,
  allowRegistration: false,
} as const;

export type FeatureFlags = Record<keyof typeof FEATURES, boolean>;

/** Formats the server accepts. `renderable` = the Android reader opens it natively. */
export const FORMATS = {
  accepted: ['epub', 'txt', 'pdf', 'mobi', 'azw', 'azw3', 'fb2', 'cbz', 'docx', 'doc', 'htmlz'],
  renderable: ['txt'],
} as const;

/** Best-effort build identifier; absent in a plain checkout, set by CI via GIT_SHA. */
export function buildRevision(): string | null {
  const fromEnv = process.env.GIT_SHA?.trim();
  if (fromEnv) return fromEnv.slice(0, 12);
  try {
    const head = readFileSync(new URL('../../.git/HEAD', import.meta.url), 'utf8').trim();
    const ref = head.startsWith('ref: ') ? head.slice(5) : null;
    if (!ref) return head.slice(0, 12);
    const loose = new URL(`../../.git/${ref}`, import.meta.url);
    return readFileSync(loose, 'utf8').trim().slice(0, 12);
  } catch {
    try {
      // Packed refs fallback.
      const packed = readFileSync(new URL('../../.git/packed-refs', import.meta.url), 'utf8');
      const line = packed.split('\n').find((l) => l.endsWith(' refs/heads/main'));
      return line ? line.trim().split(' ')[0]!.slice(0, 12) : null;
    } catch {
      return null;
    }
  }
}
