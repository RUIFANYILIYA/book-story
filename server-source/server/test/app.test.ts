import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { loadConfig } from '../src/config.ts';
import { buildApp } from '../src/app.ts';
import { FEATURES, SERVER_VERSION } from '../src/version.ts';
import { openMigratedDb } from './helpers/db.ts';

let app: FastifyInstance;

beforeAll(async () => {
  app = await buildApp({
    config: loadConfig({ NODE_ENV: 'test', EBOOK_LOG_LEVEL: 'silent' }),
    db: openMigratedDb(),
  });
  await app.ready();
});

afterAll(async () => {
  await app.close();
});

describe('GET /api/v1/meta', () => {
  it('is public and answers the discovery contract', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/v1/meta' });
    expect(res.statusCode).toBe(200);
    const body = res.json();

    expect(body.apiVersion).toBe('v1');
    expect(body.serverVersion).toBe(SERVER_VERSION);
    expect(typeof body.protocol).toBe('number');
    expect(typeof body.serverTime).toBe('string');
    expect(Number.isNaN(Date.parse(body.serverTime))).toBe(false);

    for (const flag of Object.keys(FEATURES)) {
      expect(typeof body.features[flag]).toBe('boolean');
    }
    expect(body.formats.accepted).toContain('epub');
    expect(body.formats.renderable).toEqual(['txt']);
    expect(body.limits.maxUploadBytes).toBeGreaterThan(0);
    expect(body.limits.maxBatchSize).toBe(2000);
  });

  it('reports which capabilities are intentionally not built yet', async () => {
    const body = (await app.inject('/api/v1/meta')).json();
    // The app hides UI behind these; asserting the current false set keeps a later flip honest
    // (it must be accompanied by an OpenAPI change, not a silent deploy).
    expect(body.features.batchShelf).toBe(false);
    expect(body.features.progressSync).toBe(false);
    expect(body.features.serverThumbnails).toBe(false);
    expect(body.features.rangeDownloads).toBe(true);
  });

  it('flags an ancient client for upgrade without breaking it', async () => {
    const modern = (await app.inject('/api/v1/meta')).json();
    expect(modern.upgradeRequired).toBe(false);

    const old = (
      await app.inject({ method: 'GET', url: '/api/v1/meta', headers: { 'x-app-version-code': '0' } })
    ).json();
    expect(old.upgradeRequired).toBe(true);
  });
});

describe('operational endpoints', () => {
  it('serves /health without touching the database', async () => {
    const res = await app.inject('/health');
    expect(res.statusCode).toBe(200);
    expect(res.json().status).toBe('ok');
  });

  it('serves /ready from the installed schema', async () => {
    const res = await app.inject('/ready');
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.version).toBe(SERVER_VERSION);
    // Not a literal count: it moves whenever a migration is added, and a test that fails for that
    // reason teaches nobody anything.
    expect(Number.isInteger(body.schemaVersion)).toBe(true);
    expect(body.schemaVersion).toBeGreaterThan(0);
    expect(body.instanceId).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
  });

  it('answers 503 rather than 500 when storage is not up', async () => {
    const homeless = await buildApp({
      config: loadConfig({ NODE_ENV: 'test', EBOOK_LOG_LEVEL: 'silent' }),
    });
    await homeless.ready();
    const res = await homeless.inject('/ready');
    expect(res.statusCode).toBe(503);
    expect(res.json().error.code).toBe('storage_unavailable');
    await homeless.close();
  });
});

describe('error envelope', () => {
  it('returns the closed error-code shape for an unknown route', async () => {
    const res = await app.inject('/api/v1/nope');
    expect(res.statusCode).toBe(404);
    expect(res.json()).toMatchObject({
      error: { code: 'not_found', message: expect.any(String) },
    });
    expect(res.json().error.requestId).toBeTruthy();
    expect(res.json().data).toBeUndefined();
  });

  it('does not leak internals on a 5xx', async () => {
    const throwing = await buildApp({
      config: loadConfig({ NODE_ENV: 'test', EBOOK_LOG_LEVEL: 'silent' }),
    });
    throwing.get('/boom', async () => {
      throw new Error('secret SQL fragment');
    });
    await throwing.ready();
    const res = await throwing.inject('/boom');
    expect(res.statusCode).toBe(500);
    expect(res.json().error.code).toBe('internal');
    expect(res.json().error.message).not.toContain('secret SQL fragment');
    await throwing.close();
  });
});
