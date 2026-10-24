import { describe, expect, it } from 'vitest';
import { ConfigError, loadConfig } from '../src/config.ts';

/** Values as they arrive from a `.env` file that was saved with CRLF endings on Windows:
 *  Node keeps the trailing `\r`, which silently corrupts paths and secrets. */
const crlf = (env: Record<string, string>): NodeJS.ProcessEnv =>
  Object.fromEntries(Object.entries(env).map(([k, v]) => [k, `${v}\r`]));

describe('loadConfig', () => {
  it('applies documented defaults', () => {
    const config = loadConfig({ NODE_ENV: 'test' });
    expect(config.port).toBe(8787);
    expect(config.host).toBe('0.0.0.0');
    expect(config.maxUploadBytes).toBe(512 * 1024 * 1024);
    expect(config.allowRegistration).toBe(false);
    expect(config.trustProxy).toBe(false);
    expect(config.importDir).toBeNull();
    expect(config.publicUrl).toBeNull();
  });

  it('never leaves a directory entry pointing at the database file', () => {
    const config = loadConfig({ NODE_ENV: 'test' });
    expect(config.dbFile).toBe('./data/db/ebookd.sqlite3');
    expect(Object.values(config.paths)).not.toContain(config.dbFile);
    for (const p of Object.values(config.paths)) expect(p).not.toMatch(/\.sqlite3$/);
  });

  it('survives a CRLF .env without leaking carriage returns into values', () => {
    const config = loadConfig(
      crlf({
        NODE_ENV: 'test',
        EBOOK_DATA_DIR: '/srv/db',
        EBOOK_JWT_SECRET: 'a'.repeat(43),
        EBOOK_TRUST_PROXY: 'true',
      }),
    );
    expect(config.dataDir).toBe('/srv/db');
    expect(config.jwtSecret).toBe('a'.repeat(43));
    expect(config.trustProxy).toBe(true);
    expect(JSON.stringify(config)).not.toContain('\r');
  });

  it('parses the CORS allow-list and drops blanks', () => {
    const config = loadConfig({
      NODE_ENV: 'test',
      EBOOK_CORS_ORIGINS: ' https://a.example , ,https://b.example,',
    });
    expect(config.corsOrigins).toEqual(['https://a.example', 'https://b.example']);
    expect(loadConfig({ NODE_ENV: 'test' }).corsOrigins).toEqual([]);
  });

  it('substitutes a dev key outside production so `npm run dev` stays one command', () => {
    const config = loadConfig({ NODE_ENV: 'development' });
    expect(config.jwtSecret).toMatch(/development/);
  });

  it('refuses to boot in production without both secrets', () => {
    expect(() => loadConfig({ NODE_ENV: 'production' })).toThrow(ConfigError);
    expect(() => loadConfig({ NODE_ENV: 'production' })).toThrow(/required in production/);
    expect(
      loadConfig({
        NODE_ENV: 'production',
        EBOOK_JWT_SECRET: 'x'.repeat(32),
        EBOOK_MASTER_KEY: 'y'.repeat(32),
      }).env,
    ).toBe('production');
  });

  it('rejects nonsense instead of coercing it', () => {
    expect(() => loadConfig({ NODE_ENV: 'test', EBOOK_PORT: '0' })).toThrow(ConfigError);
    expect(() => loadConfig({ NODE_ENV: 'test', EBOOK_MAX_UPLOAD_MB: 'nope' })).toThrow(ConfigError);
    expect(() =>
      loadConfig({ NODE_ENV: 'test', EBOOK_TRUST_PROXY: 'yes' }),
    ).toThrow(ConfigError);
  });
});
