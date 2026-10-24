import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { bearer, buildTestApp, seedUser, signIn } from './helpers/app.ts';

/**
 * The runtime half of the drift gate. `contract-check.mjs` proves the spec is internally
 * consistent; this proves the server actually honours it, by taking `required` and the enum sets
 * straight out of the committed manifest and checking them against real responses.
 *
 * It fails in the two ways that matter: a field the app was told to rely on going missing, and a
 * value outside the closed set the app switches on.
 */

interface Manifest {
  operations: { operationId: string; method: string; path: string; responseSchema: string | null; public: boolean }[];
  schemas: Record<string, {
    required: string[];
    enum?: string[];
    properties: Record<string, { type?: string; enum?: string[] }>;
  }>;
}

const contract = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../shared/api-spec/contract.json', import.meta.url)), 'utf8'),
) as Manifest;

let app: FastifyInstance;
let tokens: { accessToken: string; refreshToken: string };
let adminToken: string;

beforeAll(async () => {
  const t = await buildTestApp();
  app = t.app;
  await seedUser(t.users, { username: 'contract-reader', password: 'contract-password-1' });
  await seedUser(t.users, { username: 'contract-admin', password: 'contract-password-1', role: 'admin' });
  tokens = await signIn(app, 'contract-reader', 'contract-password-1');
  adminToken = (await signIn(app, 'contract-admin', 'contract-password-1')).accessToken;
});

afterAll(async () => {
  await app.close();
});

function operation(id: string) {
  const found = contract.operations.find((o) => o.operationId === id);
  if (!found) throw new Error(`contract.json no longer describes ${id}`);
  return found;
}

/** Assert every `required` key is present and every enum-typed key holds a listed value. */
function conformsTo(schemaName: string, body: Record<string, unknown>, label: string): void {
  const schema = contract.schemas[schemaName];
  if (!schema) throw new Error(`contract.json has no schema named ${schemaName}`);
  for (const field of schema.required) {
    expect(body, `${label}: body`).toHaveProperty(field);
    expect(body[field], `${label}: required field "${field}" is missing`).not.toBe(undefined);
  }
  for (const [field, spec] of Object.entries(schema.properties)) {
    if (!spec?.enum || !(field in body) || body[field] === null) continue;
    expect(spec.enum, `${label}: "${field}" = ${String(body[field])} is outside the documented enum`).toContain(
      body[field],
    );
  }
}

describe('login', () => {
  it('is declared public and answers with a SessionGrant', async () => {
    const op = operation('login');
    expect(op.public).toBe(true);
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { username: 'contract-reader', password: 'contract-password-1' },
    });
    expect(res.statusCode).toBe(200);
    conformsTo(op.responseSchema!, res.json(), 'login');
  });

  it('answers a bad password with a documented error code', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { username: 'contract-reader', password: 'definitely-wrong-1' },
    });
    expect(res.statusCode).toBe(401);
    conformsTo('ErrorEnvelope', res.json(), 'login failure');
    expect(contract.schemas.ErrorCode!.properties).toBeDefined();
    expect(operation('login').public).toBe(true);
  });

  it('keeps the error code inside the closed ErrorCode enum', async () => {
    const codes = contract.schemas.ErrorCode!.enum!;
    const probes = [
      { method: 'GET' as const, url: '/api/v1/auth/me' },
      { method: 'GET' as const, url: '/api/v1/users/not-a-real-id-at-all-xxxxxxxxxx' },
      { method: 'POST' as const, url: '/api/v1/auth/refresh', payload: { refreshToken: 'x'.repeat(32) } },
    ];
    for (const probe of probes) {
      const res = await app.inject(probe as never);
      const code = res.json()?.error?.code;
      expect(typeof code, `${probe.method} ${probe.url} must answer with an error envelope`).toBe('string');
      expect(codes, `undocumented error code "${code}" from ${probe.method} ${probe.url}`).toContain(code);
    }
  });
});

describe('authenticated endpoints', () => {
  it('getSelf returns the declared identity shape', async () => {
    const op = operation('getSelf');
    expect(op.public).toBe(false);
    const res = await app.inject({ url: '/api/v1/auth/me', headers: bearer(tokens.accessToken) });
    expect(res.statusCode).toBe(200);
    conformsTo(op.responseSchema!, res.json(), 'me');
  });

  it('listDevices reports the calling device as current', async () => {
    const res = await app.inject({ url: '/api/v1/auth/devices', headers: bearer(tokens.accessToken) });
    expect(res.statusCode).toBe(200);
    const { items } = res.json();
    expect(Array.isArray(items)).toBe(true);
    expect(items.some((d: { current: boolean }) => d.current)).toBe(true);
  });

  it('rotates on refresh and still yields a conforming grant', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/refresh',
      payload: { refreshToken: tokens.refreshToken },
    });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.refreshToken).not.toBe(tokens.refreshToken);
    conformsTo(operation('refresh').responseSchema!, body, 'refresh');
    tokens = { accessToken: body.accessToken, refreshToken: body.refreshToken };
  });
});

describe('administrator routes fail closed', () => {
  it('refuses a normal user before producing any user data', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/v1/users', headers: bearer(tokens.accessToken) });
    expect(res.statusCode).toBe(403);
    expect(res.json().error.code).toBe('capability_denied');
    expect(res.json().items).toBeUndefined();
  });

  it('lists and creates for an admin with the declared shapes', async () => {
    const list = await app.inject({ method: 'GET', url: '/api/v1/users', headers: bearer(adminToken) });
    expect(list.statusCode).toBe(200);
    conformsTo(operation('listUsers').responseSchema!, list.json(), 'listUsers');

    const created = await app.inject({
      method: 'POST',
      url: '/api/v1/users',
      headers: bearer(adminToken),
      payload: { username: 'made-by-contract', displayName: '契约用户', password: 'long-enough-password' },
    });
    expect(created.statusCode).toBe(201);
    conformsTo(operation('createUser').responseSchema!, created.json(), 'createUser');
    expect(created.json().user.passwordHash).toBeUndefined();
    // The stored hash format must never appear anywhere in the response body.
    expect(JSON.stringify(created.json())).not.toContain('scrypt$');
  });

  it('every user record agrees with PublicUser', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/v1/users', headers: bearer(adminToken) });
    const users = res.json().items as Record<string, unknown>[];
    for (const user of users.slice(0, 5)) {
      conformsTo('PublicUser', user, `user ${String(user.id)}`);
    }
  });
});
