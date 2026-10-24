import { resolve } from 'node:path';
import { z } from 'zod';

/**
 * Every runtime knob of the server, validated once at boot.
 *
 * A `.env` file saved with CRLF endings keeps its trailing `\r` in every value — Node's own
 * parser does not strip it — which silently corrupts paths, secrets and even enum matches
 * (`NODE_ENV=test\r` is not `test`). So the whole environment is normalized before any schema
 * looks at it, rather than trimming field by field and hoping none was forgotten.
 */
function normalizeEnv(raw: NodeJS.ProcessEnv): Record<string, string> {
  const normalized: Record<string, string> = {};
  for (const [key, value] of Object.entries(raw)) {
    if (typeof value === 'string') normalized[key] = value.trim();
  }
  return normalized;
}

/**
 * `.default()` must sit *before* `.transform()`: the default is an input value, so applying it
 * after the transform would have to be a boolean and would never match the env string.
 */
const booleanFromEnv = (fallback: 'true' | 'false') =>
  z
    .enum(['true', 'false', '1', '0'])
    .default(fallback)
    .transform((v) => v === 'true' || v === '1');

const EnvSchema = z.object({
  NODE_ENV: z.enum(['development', 'production', 'test']).default('development'),
  EBOOK_HOST: z.string().default('0.0.0.0'),
  EBOOK_PORT: z.coerce.number().int().min(1).max(65535).default(8787),

  /** Root for the SQLite database. Must be a POSIX-local filesystem: WAL on a 9p//mnt/c
   *  bind mount has no working advisory locking and will corrupt. */
  EBOOK_DATA_DIR: z.string().default('./data/db'),
  /** Root for content-addressed originals, covers, uploads-in-flight and trash. */
  EBOOK_LIBRARY_DIR: z.string().default('./data/library'),
  /** Optional read-only drop folder for bulk server-side import. */
  EBOOK_IMPORT_DIR: z.string().default(''),
  /**
   * Folder watched for new books. Files found there are catalogued *in place* — the bytes are not
   * copied or moved, the library only records the path. Empty (the default) disables the watcher.
   * Mount it read-only: the server has no reason to write to it, and a read-only mount is a
   * guarantee rather than a promise.
   */
  EBOOK_WATCH_DIR: z.string().default(''),
  /** Seconds between scans of the watch folder. A scan is a stat per known file, not a re-hash. */
  EBOOK_WATCH_INTERVAL: z.coerce.number().int().min(5).max(86_400).default(60),
  /** Absolute base URL advertised in generated download links; empty means derive from request. */
  EBOOK_PUBLIC_URL: z.string().default(''),

  EBOOK_LOG_LEVEL: z
    .enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'])
    .default('info'),
  EBOOK_TRUST_PROXY: booleanFromEnv('false'),
  EBOOK_ALLOW_REGISTRATION: booleanFromEnv('false'),
  /** Comma-separated allow-list. Empty (the default) means no cross-origin browser client is
   *  permitted at all: the Android app sends its own headers and the admin UI is same-origin,
   *  so opening CORS by default would only widen the blast radius on a LAN. */
  EBOOK_CORS_ORIGINS: z.string().default(''),

  EBOOK_MAX_UPLOAD_MB: z.coerce.number().int().min(1).max(20480).default(512),
  /** Deleted book files stay readable for in-flight downloads and other devices' partials. */
  EBOOK_FILE_RETENTION_DAYS: z.coerce.number().int().min(0).max(365).default(7),
  /** How often the retention sweep runs. It is idempotent, so overshooting only costs a little
   *  disk held past the window. The floor is 1 rather than 0 on purpose: there is no supported
   *  way to disable the sweep, because the failure it prevents is unbounded disk growth. */
  EBOOK_SWEEP_INTERVAL_HOURS: z.coerce.number().int().min(1).max(168).default(6),

  EBOOK_ACCESS_TTL_SECONDS: z.coerce.number().int().min(60).max(86400).default(900),
  EBOOK_REFRESH_IDLE_DAYS: z.coerce.number().int().min(1).max(365).default(30),
  EBOOK_REFRESH_ABSOLUTE_DAYS: z.coerce.number().int().min(1).max(3650).default(90),

  /** 256-bit secret for HS256 access tokens. Required outside development/test. */
  EBOOK_JWT_SECRET: z.string().default(''),
  /** Key-encryption key for at-rest secrets (signed-URL HMAC, future per-user keys). */
  EBOOK_MASTER_KEY: z.string().default(''),

  /** First admin. With no password supplied one is generated and logged exactly once. */
  EBOOK_ADMIN_USER: z.string().default('admin'),
  EBOOK_ADMIN_PASSWORD: z.string().default(''),
});

export type Config = {
  env: 'development' | 'production' | 'test';
  host: string;
  port: number;
  dataDir: string;
  libraryDir: string;
  importDir: string | null;
  /** Absolute folder catalogued in place; null when the watcher is off. */
  watchDir: string | null;
  watchIntervalSeconds: number;
  publicUrl: string | null;
  logLevel: string;
  trustProxy: boolean;
  allowRegistration: boolean;
  corsOrigins: string[];
  maxUploadBytes: number;
  fileRetentionDays: number;
  sweepIntervalHours: number;
  accessTtlSeconds: number;
  refreshIdleDays: number;
  refreshAbsoluteDays: number;
  jwtSecret: string;
  masterKey: string;
  adminUser: string;
  adminPassword: string | null;
  /** SQLite file. Its parent is `dataDir`; it is deliberately not in `paths` so that callers
   *  that mkdir every entry in `paths` cannot create a directory named after the database. */
  dbFile: string;
  paths: {
    blobs: string;
    covers: string;
    userCovers: string;
    uploadsTmp: string;
    trash: string;
    imports: string;
    backups: string;
  };
};

export class ConfigError extends Error {
  constructor(issues: string[]) {
    super(`Invalid configuration:\n  - ${issues.join('\n  - ')}`);
    this.name = 'ConfigError';
  }
}

export function loadConfig(raw: NodeJS.ProcessEnv = process.env): Config {
  const parsed = EnvSchema.safeParse(normalizeEnv(raw));
  if (!parsed.success) {
    throw new ConfigError(
      parsed.error.issues.map((i) => `${i.path.join('.') || '(root)'}: ${i.message}`),
    );
  }
  const e = parsed.data;

  // Fail fast instead of booting an insecure public service. Development and the test suite
  // get an explicit dev key so `npm run dev` stays a one-liner.
  const insecure = e.EBOOK_JWT_SECRET.length === 0 || e.EBOOK_MASTER_KEY.length === 0;
  if (insecure && e.NODE_ENV === 'production') {
    throw new ConfigError([
      'EBOOK_JWT_SECRET and EBOOK_MASTER_KEY are required in production.',
      'Generate one each with: node -e "console.log(require("crypto").randomBytes(32).toString("base64url"))"',
    ]);
  }

  const devFallback = 'ebookd-insecure-development-secret-do-not-use-in-production';
  const libraryDir = e.EBOOK_LIBRARY_DIR;

  return {
    env: e.NODE_ENV,
    host: e.EBOOK_HOST,
    port: e.EBOOK_PORT,
    dataDir: e.EBOOK_DATA_DIR,
    libraryDir,
    importDir: e.EBOOK_IMPORT_DIR.length > 0 ? e.EBOOK_IMPORT_DIR : null,
    watchDir: e.EBOOK_WATCH_DIR.length > 0 ? resolve(e.EBOOK_WATCH_DIR) : null,
    watchIntervalSeconds: e.EBOOK_WATCH_INTERVAL,
    publicUrl: e.EBOOK_PUBLIC_URL.length > 0 ? e.EBOOK_PUBLIC_URL : null,
    logLevel: e.EBOOK_LOG_LEVEL,
    trustProxy: e.EBOOK_TRUST_PROXY,
    allowRegistration: e.EBOOK_ALLOW_REGISTRATION,
    corsOrigins: e.EBOOK_CORS_ORIGINS.split(',')
      .map((o) => o.trim())
      .filter((o) => o.length > 0),
    maxUploadBytes: e.EBOOK_MAX_UPLOAD_MB * 1024 * 1024,
    fileRetentionDays: e.EBOOK_FILE_RETENTION_DAYS,
    sweepIntervalHours: e.EBOOK_SWEEP_INTERVAL_HOURS,
    accessTtlSeconds: e.EBOOK_ACCESS_TTL_SECONDS,
    refreshIdleDays: e.EBOOK_REFRESH_IDLE_DAYS,
    refreshAbsoluteDays: e.EBOOK_REFRESH_ABSOLUTE_DAYS,
    jwtSecret: e.EBOOK_JWT_SECRET.length > 0 ? e.EBOOK_JWT_SECRET : devFallback,
    masterKey: e.EBOOK_MASTER_KEY.length > 0 ? e.EBOOK_MASTER_KEY : devFallback,
    adminUser: e.EBOOK_ADMIN_USER,
    adminPassword: e.EBOOK_ADMIN_PASSWORD.length > 0 ? e.EBOOK_ADMIN_PASSWORD : null,
    dbFile: `${e.EBOOK_DATA_DIR}/ebookd.sqlite3`,
    paths: {
      blobs: `${libraryDir}/blobs`,
      covers: `${libraryDir}/covers`,
      userCovers: `${libraryDir}/covers/users`,
      uploadsTmp: `${libraryDir}/tmp/uploads`,
      trash: `${libraryDir}/trash`,
      imports: `${libraryDir}/imports`,
      backups: `${libraryDir}/backups`,
    },
  };
}
