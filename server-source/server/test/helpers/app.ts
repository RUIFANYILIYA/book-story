import type { FastifyInstance } from 'fastify';
import { loadConfig } from '../../src/config.ts';
import { buildApp } from '../../src/app.ts';
import { openMigratedDb } from './db.ts';
import { UserRepo } from '../../src/db/repositories/user.repo.ts';
import { hashPassword } from '../../src/auth/password.ts';
import type { Db } from '../../src/db/index.ts';

export interface TestApp {
  app: FastifyInstance;
  db: Db;
  users: UserRepo;
}

export async function buildTestApp(): Promise<TestApp> {
  const db = openMigratedDb();
  const app = await buildApp({
    config: loadConfig({
      NODE_ENV: 'test',
      EBOOK_LOG_LEVEL: 'silent',
      EBOOK_JWT_SECRET: 'test-secret-test-secret-test-secret-1234567890',
      EBOOK_REFRESH_IDLE_DAYS: '30',
    }),
    db,
  });
  await app.ready();
  return { app, db, users: new UserRepo(db) };
}

export async function seedUser(
  users: UserRepo,
  input: { username: string; password: string; role?: 'admin' | 'user'; mustChangePw?: boolean },
): Promise<string> {
  const row = users.create({
    username: input.username,
    displayName: input.username,
    passwordHash: await hashPassword(input.password),
    role: input.role ?? 'user',
    mustChangePw: input.mustChangePw ?? false,
  });
  return row.id;
}

/** Login and return the token pair, failing the test loudly if the round trip did not work. */
export async function signIn(
  app: FastifyInstance,
  username: string,
  password: string,
): Promise<{ accessToken: string; refreshToken: string; deviceId: string }> {
  const res = await app.inject({
    method: 'POST',
    url: '/api/v1/auth/login',
    payload: { username, password },
  });
  if (res.statusCode !== 200) {
    throw new Error(`login failed: ${res.statusCode} ${res.body}`);
  }
  const body = res.json();
  return { accessToken: body.accessToken, refreshToken: body.refreshToken, deviceId: body.deviceId };
}

export function bearer(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}` };
}
