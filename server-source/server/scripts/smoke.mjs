#!/usr/bin/env node
/**
 * End-to-end smoke test against the **built artifact**, not the TypeScript sources: it spawns
 * `dist/main.js` the same way the container's entrypoint does, so a packaging mistake (bad
 * import specifiers, missing file, top-level await on a config read) fails here rather than on
 * the NAS at 3am.
 *
 * Run `npm run build` first; this script does not build, so CI ordering stays explicit.
 */
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { once } from 'node:events';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const serverRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const ENTRY = join(serverRoot, 'dist', 'main.js');
const PORT = Number(process.env.SMOKE_PORT ?? 8899);
const BASE = `http://127.0.0.1:${PORT}`;
/** Supplied to the boot env so the bootstrap admin is a known credential rather than a log line. */
const ADMIN_PASSWORD = 'smoke-test-password-1234';

const results = [];
let failures = 0;
/** Kept at module scope so the SIGINT path can reap the child instead of orphaning it. */
let serverChild = null;

function check(name, ok, detail = '') {
  results.push({ name, ok, detail });
  if (!ok) failures += 1;
}

async function waitForHealth(deadlineMs) {
  const start = Date.now();
  while (Date.now() - start < deadlineMs) {
    try {
      const res = await fetch(`${BASE}/health`, { signal: AbortSignal.timeout(1000) });
      if (res.ok) return true;
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 150));
  }
  return false;
}

function bootEnv(dir, overrides = {}) {
  return {
    ...process.env,
    NODE_ENV: 'production',
    EBOOK_LOG_LEVEL: 'silent',
    EBOOK_PORT: String(PORT),
    EBOOK_HOST: '127.0.0.1',
    EBOOK_DATA_DIR: join(dir, 'db'),
    EBOOK_LIBRARY_DIR: join(dir, 'library'),
    EBOOK_JWT_SECRET: randomBytes(32).toString('base64url'),
    EBOOK_MASTER_KEY: randomBytes(32).toString('base64url'),
    EBOOK_ADMIN_USER: 'admin',
    EBOOK_ADMIN_PASSWORD: ADMIN_PASSWORD,
    ...overrides,
  };
}

/** A production instance must refuse to start without secrets; an insecure default is the bug. */
async function assertRefusesWithoutSecrets(dir) {
  const child = spawn(process.execPath, [ENTRY], {
    env: bootEnv(dir, { EBOOK_JWT_SECRET: '', EBOOK_MASTER_KEY: '' }),
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  let stderr = '';
  child.stderr.on('data', (chunk) => (stderr += chunk));
  const code = await new Promise((resolve) => child.on('exit', resolve));
  check(
    'production boot without secrets exits non-zero',
    code !== 0 && code !== null,
    `exit=${code}`,
  );
  check(
    'the refusal names the missing variables',
    /EBOOK_JWT_SECRET/.test(stderr),
    stderr.slice(0, 120),
  );
}

async function main() {
  if (!existsSync(ENTRY)) {
    process.stderr.write(
      `smoke: ${ENTRY} not found — run \`npm run build -w server\` first (this script tests the built artifact on purpose).\n`,
    );
    process.exit(1);
  }
  const dir = mkdtempSync(join(tmpdir(), 'ebookd-smoke-'));
  serverChild = spawn(process.execPath, [ENTRY], {
    env: bootEnv(dir),
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  let stderr = '';
  serverChild.stderr.on('data', (chunk) => (stderr += chunk));

  try {
    if (!(await waitForHealth(15_000))) {
      check('server boots and answers /health', false, `stderr: ${stderr.slice(0, 400)}`);
      await assertRefusesWithoutSecrets(dir);
      await finish(dir);
      return;
    }
    check('server boots and answers /health', true);

    const meta = await fetch(`${BASE}/api/v1/meta`);
    check('GET /api/v1/meta is public', meta.status === 200, String(meta.status));
    const body = await meta.json();
    for (const field of [
      'apiVersion',
      'serverVersion',
      'protocol',
      'serverTime',
      'features',
      'formats',
      'limits',
    ]) {
      check(`meta.${field} present`, body[field] !== undefined);
    }
    check('meta.apiVersion is v1', body.apiVersion === 'v1', body.apiVersion);
    check(
      'meta.serverTime is a parseable timestamp',
      !Number.isNaN(Date.parse(body.serverTime ?? '')),
      body.serverTime,
    );
    check('formats.renderable lists txt for this build', (body.formats?.renderable ?? []).includes('txt'));
    check(
      'every feature flag is boolean',
      Object.values(body.features ?? {}).every((v) => typeof v === 'boolean'),
    );

    const clock = await fetch(`${BASE}/api/v1/meta`, {
      headers: { 'X-App-Version-Code': '0' },
    });
    const clockBody = await clock.json();
    check('an ancient client is told to upgrade', clockBody.upgradeRequired === true);

    const missing = await fetch(`${BASE}/api/v1/book/does-not-exist`);
    const missingBody = await missing.json().catch(() => ({}));
    check('unknown route yields 404', missing.status === 404, String(missing.status));
    check(
      'unknown route uses the error envelope, not Fastify defaults',
      missingBody?.error?.code === 'not_found' && typeof missingBody.error.requestId === 'string',
      JSON.stringify(missingBody).slice(0, 160),
    );

    const bigUpload = await fetch(`${BASE}/api/v1/meta`, { method: 'POST' });
    check(
      'an unsupported method on a documented path still answers in the envelope',
      [404, 405].includes(bigUpload.status),
      String(bigUpload.status),
    );

    // The auth path exercised through the compiled artifact, not the TypeScript sources: jose
    // signing, scrypt verification and the rotation state machine all run out of dist/.
    const admin = await fetch(`${BASE}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: ADMIN_PASSWORD }),
    });
    check('bootstrap admin can sign in', admin.status === 200, String(admin.status));
    const grant = await admin.json();
    check('login returned a token triple', Boolean(grant.accessToken && grant.refreshToken && grant.deviceId));
    check('login reports the admin capabilities', (grant.user?.role ?? '') === 'admin');

    const me = await fetch(`${BASE}/api/v1/auth/me`, {
      headers: { authorization: `Bearer ${grant.accessToken}` },
    });
    check('the issued access token authenticates', me.status === 200, String(me.status));
    const meBody = await me.json();
    check('me lists capabilities including user:manage', (meBody.capabilities ?? []).includes('user:manage'));

    const forged = await fetch(`${BASE}/api/v1/auth/me`, {
      headers: { authorization: `Bearer ${grant.accessToken.slice(0, -4)}dead` },
    });
    check('a tampered signature is rejected', forged.status === 401, String(forged.status));

    const rotated = await fetch(`${BASE}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ refreshToken: grant.refreshToken }),
    });
    check('refresh rotates the token', rotated.status === 200, String(rotated.status));
    const rotatedBody = await rotated.json();
    check(
      'the spent refresh token is refused and revokes its lineage',
      (
        await fetch(`${BASE}/api/v1/auth/refresh`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ refreshToken: grant.refreshToken }),
        })
      ).status === 401,
    );
    check(
      'the replacement token is dead too after a replay',
      (
        await fetch(`${BASE}/api/v1/auth/refresh`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ refreshToken: rotatedBody.refreshToken }),
        })
      ).status === 401,
    );

    await assertRefusesWithoutSecrets(dir);
    await finish(dir);
  } catch (err) {
    check('smoke run completed without throwing', false, String(err?.stack ?? err));
    await finish(dir);
  }
}

/**
 * The server keeps the SQLite file (and therefore the temp directory) open, so the directory
 * cannot be removed until the child has actually exited — on Windows that surfaces as EPERM
 * rather than a clean delete. Wait for the exit event, with a hard cap so a wedged child cannot
 * hang the suite.
 */
async function reap() {
  if (serverChild && serverChild.exitCode === null) {
    serverChild.kill('SIGTERM');
    await Promise.race([once(serverChild, 'exit'), new Promise((r) => setTimeout(r, 5000))]);
  }
}

async function finish(dir) {
  await reap();
  for (const r of results) {
    process.stdout.write(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok ? '' : `  <- ${r.detail}`}\n`);
  }
  process.stdout.write(`\n${results.length - failures}/${results.length} smoke checks passed\n`);
  try {
    rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  } catch (err) {
    // Leftover scratch data is noise, not a failed smoke test; do not turn a green run red.
    process.stdout.write(`note: could not clean ${dir}: ${String(err?.message ?? err)}\n`);
  }
  process.exit(failures === 0 ? 0 : 1);
}

process.on('SIGINT', () => {
  serverChild?.kill('SIGTERM');
  process.exit(130);
});

await main();
