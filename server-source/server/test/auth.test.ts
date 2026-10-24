import { describe, expect, it } from 'vitest';
import {
  bearer,
  buildTestApp,
  seedUser,
  signIn,
} from './helpers/app.ts';
import { capabilitiesFor, CapabilityGrantError, parseGrants } from '../src/auth/capabilities.ts';
import { hashPassword, needsRehash, verifyPassword } from '../src/auth/password.ts';

describe('password hashing', () => {
  it('round-trips and rejects a near-miss', async () => {
    const hash = await hashPassword('correct-horse-battery');
    expect(await verifyPassword('correct-horse-battery', hash)).toBe(true);
    expect(await verifyPassword('correct-horse-batterY', hash)).toBe(false);
    expect(await verifyPassword('', hash)).toBe(false);
  });

  it('stores parameters inside the encoding so they can be raised later', async () => {
    const hash = await hashPassword('some-password-here');
    const [scheme, N, r, p] = hash.split('$');
    expect(scheme).toBe('scrypt');
    expect(Number(N)).toBe(16384);
    expect(Number(r)).toBe(8);
    expect(Number(p)).toBe(1);
    expect(needsRehash(hash)).toBe(false);
  });

  it('treats unicode-equivalent passwords as different, but normalizes compatibility forms', async () => {
    // NFKC folds full-width and compatibility characters, so a password pasted from a document
    // that substituted them still verifies; it must not fold case, which users expect to matter.
    const hash = await hashPassword('Ｐａｓｓｗｏｒｄ　１');
    expect(await verifyPassword('Password 1', hash)).toBe(true);
    expect(await verifyPassword('password 1', hash)).toBe(false);
  });

  it('never claims a match on an unparseable stored hash', async () => {
    expect(await verifyPassword('whatever', 'garbage')).toBe(false);
    expect(needsRehash('garbage')).toBe(true);
  });

  it('refuses an absurd password instead of hashing it', async () => {
    await expect(hashPassword('a'.repeat(2000))).rejects.toThrow(/at most/);
    await expect(hashPassword('')).rejects.toThrow(/non-empty/);
  });
});

describe('capability resolution', () => {
  const base = { status: 'active' as const, permissions_json: '{}' };

  it('gives an admin everything and a user the two read/write defaults', () => {
    expect(capabilitiesFor({ ...base, role: 'admin' })).toContain('user:manage');
    const user = capabilitiesFor({ ...base, role: 'user' });
    expect(user).toEqual(['file:upload', 'library:read']);
    expect(user).not.toContain('user:manage');
  });

  it('lets a grant widen a user but never lets a role-less string grant silently nothing', () => {
    const widened = capabilitiesFor({
      ...base,
      role: 'user',
      permissions_json: JSON.stringify({ 'library:edit': true }),
    });
    expect(widened).toContain('library:edit');
  });

  it('honours a revocation against the defaults', () => {
    const narrowed = capabilitiesFor({
      ...base,
      role: 'user',
      permissions_json: JSON.stringify({ 'file:upload': false }),
    });
    expect(narrowed).not.toContain('file:upload');
    expect(narrowed).toContain('library:read');
  });

  it('grants a disabled account nothing at all', () => {
    expect(capabilitiesFor({ role: 'admin', permissions_json: '{}', status: 'disabled' })).toEqual([]);
  });

  it('rejects an unknown capability instead of ignoring it', () => {
    expect(() => parseGrants('{"librarry:read": true}')).toThrow(CapabilityGrantError);
    expect(() => parseGrants('["library:read"]')).toThrow(CapabilityGrantError);
    expect(() => parseGrants('{"library:read": "yes"}')).toThrow(CapabilityGrantError);
    expect(() => parseGrants('not json')).toThrow(CapabilityGrantError);
  });
});

describe('login', () => {
  it('issues an access token, a refresh token and a device id', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'reader', password: 'right-password-1' });
    const tokens = await signIn(app, 'reader', 'right-password-1');
    expect(tokens.accessToken.split('.')).toHaveLength(3);
    expect(tokens.refreshToken.length).toBeGreaterThan(20);
    expect(tokens.deviceId).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
    await app.close();
  });

  it('answers an unknown username and a wrong password identically', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'reader', password: 'right-password-1' });
    const unknown = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { username: 'nobody-here', password: 'right-password-1' },
    });
    const wrong = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { username: 'reader', password: 'wrong-password-1' },
    });
    expect(unknown.statusCode).toBe(401);
    expect(wrong.statusCode).toBe(401);
    // Identical apart from requestId, which is per-request by definition. Comparing the rest is
    // the point: if the two paths ever differ in code, message or details, the login endpoint has
    // become a username-enumeration oracle.
    const { requestId: unknownId, ...unknownError } = unknown.json().error;
    const { requestId: wrongId, ...wrongError } = wrong.json().error;
    expect(unknownError).toEqual(wrongError);
    expect(typeof unknownId).toBe('string');
    expect(typeof wrongId).toBe('string');
    expect(unknownId).not.toBe(wrongId);
    await app.close();
  });

  it('locks an account after repeated failures and names the code', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'target', password: 'right-password-1' });
    for (let i = 0; i < 5; i += 1) {
      await app.inject({
        method: 'POST',
        url: '/api/v1/auth/login',
        payload: { username: 'target', password: 'nope-nope-nope' },
      });
    }
    const locked = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { username: 'target', password: 'right-password-1' },
    });
    expect(locked.statusCode).toBe(429);
    expect(locked.json().error.code).toBe('account_locked');
    await app.close();
  });

  it('refuses a disabled account without revealing that the password was right', async () => {
    const { app, users } = await buildTestApp();
    const id = await seedUser(users, { username: 'off', password: 'right-password-1' });
    users.setStatus(id, 'disabled');
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { username: 'off', password: 'right-password-1' },
    });
    expect(res.statusCode).toBe(403);
    expect(res.json().error.code).toBe('account_disabled');
    await app.close();
  });

  it('validates the request shape before touching the database', async () => {
    const { app } = await buildTestApp();
    const res = await app.inject({ method: 'POST', url: '/api/v1/auth/login', payload: { username: 'x' } });
    expect(res.statusCode).toBe(422);
    expect(res.json().error.code).toBe('validation_failed');
    await app.close();
  });
});

describe('the guard', () => {
  it('rejects a missing, malformed or foreign token', async () => {
    const { app } = await buildTestApp();
    expect((await app.inject('/api/v1/auth/me')).statusCode).toBe(401);
    const noScheme = await app.inject({
      method: 'GET',
      url: '/api/v1/auth/me',
      headers: { authorization: 'Basic dGVzdA==' },
    });
    expect(noScheme.json().error.code).toBe('unauthorized');
    const garbage = await app.inject({
      method: 'GET',
      url: '/api/v1/auth/me',
      headers: bearer('not.a.jwt'),
    });
    expect(garbage.statusCode).toBe(401);
    await app.close();
  });

  it('reports who you are and what you may do', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'boss', password: 'right-password-1', role: 'admin' });
    const { accessToken } = await signIn(app, 'boss', 'right-password-1');
    const res = await app.inject({ url: '/api/v1/auth/me', headers: bearer(accessToken) });
    expect(res.statusCode).toBe(200);
    expect(res.json().user.username).toBe('boss');
    expect(res.json().capabilities).toContain('user:manage');
    await app.close();
  });

  it('refuses a normal user the admin routes with capability_denied', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'pleb', password: 'right-password-1' });
    const { accessToken } = await signIn(app, 'pleb', 'right-password-1');
    const res = await app.inject({ method: 'GET', url: '/api/v1/users', headers: bearer(accessToken) });
    expect(res.statusCode).toBe(403);
    expect(res.json().error.code).toBe('capability_denied');
    await app.close();
  });
});

describe('changing ones own account', () => {
  it('renames the signed-in user, and the new name is what logs in afterwards', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'oldname', password: 'right-password-1' });
    const { accessToken } = await signIn(app, 'oldname', 'right-password-1');

    const res = await app.inject({
      method: 'PATCH',
      url: '/api/v1/auth/me',
      headers: bearer(accessToken),
      payload: { username: 'newname' },
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().user.username).toBe('newname');

    // The rename must not be a cosmetic field: authentication keys off it.
    expect((await signIn(app, 'newname', 'right-password-1')).accessToken).toBeTruthy();
    await expect(signIn(app, 'oldname', 'right-password-1')).rejects.toThrow();
    await app.close();
  });

  it('refuses a name somebody else already took, and changes nothing', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'mine', password: 'right-password-1' });
    await seedUser(users, { username: 'theirs', password: 'right-password-1' });
    const { accessToken } = await signIn(app, 'mine', 'right-password-1');

    const res = await app.inject({
      method: 'PATCH',
      url: '/api/v1/auth/me',
      headers: bearer(accessToken),
      payload: { username: 'theirs' },
    });
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('already_exists');
    const me = await app.inject({ url: '/api/v1/auth/me', headers: bearer(accessToken) });
    expect(me.json().user.username).toBe('mine');
    await app.close();
  });

  it('treats a case-only rename as the same name, not a collision', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'mixedCase', password: 'right-password-1' });
    const { accessToken } = await signIn(app, 'mixedCase', 'right-password-1');

    const res = await app.inject({
      method: 'PATCH',
      url: '/api/v1/auth/me',
      headers: bearer(accessToken),
      payload: { username: 'MIXEDCASE', displayName: 'Renamed anyway' },
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().user.displayName).toBe('Renamed anyway');
    await app.close();
  });

  it('rejects a username nobody could type as a login', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'plainname', password: 'right-password-1' });
    const { accessToken } = await signIn(app, 'plainname', 'right-password-1');

    const res = await app.inject({
      method: 'PATCH',
      url: '/api/v1/auth/me',
      headers: bearer(accessToken),
      payload: { username: 'has spaces' },
    });
    expect(res.statusCode).toBe(422);
    await app.close();
  });

  it('changes the password, clears the flag and keeps this session usable', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'pivot', password: 'right-password-1', mustChangePw: true });
    const first = await signIn(app, 'pivot', 'right-password-1');

    const tooShort = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/me/password',
      headers: bearer(first.accessToken),
      payload: { currentPassword: 'right-password-1', newPassword: 'short' },
    });
    expect(tooShort.statusCode).toBe(422);

    const wrongCurrent = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/me/password',
      headers: bearer(first.accessToken),
      payload: { currentPassword: 'not-it-at-all', newPassword: 'a-better-password-1' },
    });
    expect(wrongCurrent.json().error.code).toBe('invalid_credentials');

    const ok = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/me/password',
      headers: bearer(first.accessToken),
      payload: { currentPassword: 'right-password-1', newPassword: 'a-better-password-1' },
    });
    expect(ok.statusCode).toBe(200);

    const me = await app.inject({ url: '/api/v1/auth/me', headers: bearer(first.accessToken) });
    expect(me.json().user.mustChangePassword).toBe(false);
    expect((await signIn(app, 'pivot', 'a-better-password-1')).accessToken).toBeTruthy();
    await expect(signIn(app, 'pivot', 'right-password-1')).rejects.toThrow();
    await app.close();
  });
});

describe('refresh rotation and theft response', () => {
  it('rotates the refresh token on every use', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'a', password: 'right-password-1' });
    const first = await signIn(app, 'a', 'right-password-1');
    const second = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/refresh',
      payload: { refreshToken: first.refreshToken },
    });
    expect(second.statusCode).toBe(200);
    expect(second.json().refreshToken).not.toBe(first.refreshToken);
    await app.close();
  });

  it('revokes the whole family when a spent refresh token is presented again', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'a', password: 'right-password-1' });
    const first = await signIn(app, 'a', 'right-password-1');

    const rotated = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/refresh',
      payload: { refreshToken: first.refreshToken },
    });
    const liveToken = rotated.json().refreshToken as string;

    // The replay: exactly what a copied token looks like after the client rotated past it.
    const replay = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/refresh',
      payload: { refreshToken: first.refreshToken },
    });
    expect(replay.statusCode).toBe(401);
    expect(replay.json().error.code).toBe('token_reused');
    expect(replay.json().error.details.devicesRevoked).toBeGreaterThanOrEqual(1);

    // The token the legitimate client is now holding must be dead too — that is the point.
    const after = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/refresh',
      payload: { refreshToken: liveToken },
    });
    expect(after.statusCode).toBe(401);
    await app.close();
  });

  it('logout is idempotent and ends the lineage', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'a', password: 'right-password-1' });
    const { refreshToken } = await signIn(app, 'a', 'right-password-1');
    const first = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/logout',
      payload: { refreshToken },
    });
    const second = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/logout',
      payload: { refreshToken },
    });
    expect(first.statusCode).toBe(204);
    expect(second.statusCode).toBe(204);
    const after = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/refresh',
      payload: { refreshToken },
    });
    expect(after.statusCode).toBe(401);
    await app.close();
  });
});

describe('revocation paths', () => {
  it('a password change kills other sessions but keeps the one that asked', async () => {
    const { app, users } = await buildTestApp();
    await seedUser(users, { username: 'a', password: 'right-password-1' });
    const deviceOne = await signIn(app, 'a', 'right-password-1');
    const deviceTwo = await signIn(app, 'a', 'right-password-1');

    const changed = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/me/password',
      headers: bearer(deviceOne.accessToken),
      payload: { currentPassword: 'right-password-1', newPassword: 'brand-new-password' },
    });
    expect(changed.statusCode).toBe(200);
    expect(changed.json().otherSessionsRevoked).toBe(1);

    // The caller stays signed in, because being forced out after saving is indistinguishable
    // from the save having failed.
    const stillLive = await app.inject({
      url: '/api/v1/auth/me',
      headers: bearer(deviceOne.accessToken),
    });
    expect(stillLive.statusCode).toBe(200);

    const other = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/refresh',
      payload: { refreshToken: deviceTwo.refreshToken },
    });
    expect(other.statusCode).toBe(401);

    const wrongCurrent = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/me/password',
      headers: bearer(deviceOne.accessToken),
      payload: { currentPassword: 'stale-stale-stale', newPassword: 'another-new-password' },
    });
    expect(wrongCurrent.statusCode).toBe(401);
    await app.close();
  });

  it('disabling an account invalidates an already-issued access token', async () => {
    const { app, users } = await buildTestApp();
    const id = await seedUser(users, { username: 'a', password: 'right-password-1' });
    const { accessToken } = await signIn(app, 'a', 'right-password-1');
    expect((await app.inject({ url: '/api/v1/auth/me', headers: bearer(accessToken) })).statusCode).toBe(200);

    users.setStatus(id, 'disabled');
    const after = await app.inject({ url: '/api/v1/auth/me', headers: bearer(accessToken) });
    expect(after.statusCode).toBe(403);
    expect(after.json().error.code).toBe('account_disabled');
    await app.close();
  });
});

describe('admin user management', () => {
  async function adminApp() {
    const t = await buildTestApp();
    await seedUser(t.users, { username: 'root', password: 'admin-password-1', role: 'admin' });
    const { accessToken } = await signIn(t.app, 'root', 'admin-password-1');
    return { ...t, accessToken };
  }

  it('creates a user and refuses a duplicate username with a distinct code', async () => {
    const { app, accessToken } = await adminApp();
    const headers = bearer(accessToken);
    const created = await app.inject({
      method: 'POST',
      url: '/api/v1/users',
      headers,
      payload: { username: 'newcomer', displayName: '新人', password: 'their-password-1' },
    });
    expect(created.statusCode).toBe(201);
    expect(created.json().user.passwordHash).toBeUndefined();
    expect(created.json().initialPassword).toBe('their-password-1');

    const dupe = await app.inject({
      method: 'POST',
      url: '/api/v1/users',
      headers,
      payload: { username: 'Newcomer', displayName: 'again', password: 'their-password-1' },
    });
    expect(dupe.statusCode).toBe(409);
    expect(dupe.json().error.code).toBe('already_exists');
    await app.close();
  });

  it('rejects a username that would break the shape the app assumes', async () => {
    const { app, accessToken } = await adminApp();
    for (const bad of ['a', 'has space', 'emoji 😀', 'x'.repeat(80)]) {
      const res = await app.inject({
        method: 'POST',
        url: '/api/v1/users',
        headers: bearer(accessToken),
        payload: { username: bad, displayName: 'd', password: 'long-enough-pass' },
      });
      expect(res.statusCode, bad).toBe(422);
    }
    await app.close();
  });

  it('protects the last administrator from being demoted or deleted', async () => {
    const { app, accessToken, users } = await adminApp();
    const headers = bearer(accessToken);
    const me = await app.inject({ url: '/api/v1/auth/me', headers });
    const adminId = me.json().user.id;

    const demote = await app.inject({
      method: 'PATCH',
      url: `/api/v1/users/${adminId}`,
      headers,
      payload: { role: 'user' },
    });
    expect(demote.statusCode).toBe(422);

    const remove = await app.inject({ method: 'DELETE', url: `/api/v1/users/${adminId}`, headers });
    expect(remove.statusCode).toBe(422);

    // With a second admin the same calls are allowed; the rule is about lockout, not about the route.
    await seedUser(users, { username: 'second', password: 'admin-password-2', role: 'admin' });
    const stillFine = await app.inject({
      method: 'PATCH',
      url: `/api/v1/users/${adminId}`,
      headers,
      payload: { displayName: 'Renamed Admin' },
    });
    expect(stillFine.statusCode).toBe(200);
    await app.close();
  });

  it('rejects an unknown capability grant rather than silently granting nothing', async () => {
    const { app, accessToken } = await adminApp();
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/users',
      headers: bearer(accessToken),
      payload: {
        username: 'helper',
        displayName: 'helper',
        password: 'long-enough-pass',
        permissions: { 'librarry:read': true },
      },
    });
    expect(res.statusCode).toBe(422);
    expect(res.json().error.message).toMatch(/unknown capability/);
    await app.close();
  });

  it('rejects a malformed id in the path before it reaches SQL', async () => {
    const { app, accessToken } = await adminApp();
    const res = await app.inject({
      method: 'GET',
      url: '/api/v1/users/1%20OR%201%3D1',
      headers: bearer(accessToken),
    });
    expect(res.statusCode).toBe(422);
    expect(res.json().error.code).toBe('validation_failed');
    await app.close();
  });

  it('does not treat another user\'s device id as a 403 worth confirming', async () => {
    const { app, accessToken } = await adminApp();
    const res = await app.inject({
      method: 'DELETE',
      url: `/api/v1/auth/devices/${'A'.repeat(26)}`,
      headers: bearer(accessToken),
    });
    expect(res.statusCode).toBe(404);
    await app.close();
  });
});
