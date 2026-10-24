import { randomBytes } from 'node:crypto';
import type { Config } from '../config.ts';
import type { Db } from '../db/index.ts';
import { UserRepo } from '../db/repositories/user.repo.ts';
import { hashPassword } from './password.ts';

export interface BootstrapResult {
  created: boolean;
  username: string;
  /** Present only when a password had to be invented; log it once and never persist it. */
  generatedPassword?: string;
}

/**
 * Guarantees at least one admin exists so a fresh volume is not a locked door.
 *
 * Called after migrations, before listen. If `EBOOK_ADMIN_PASSWORD` is unset a strong one is
 * generated and returned for a single log line — the alternative (a default like `admin/admin`)
 * is a server that is silently open on a NAS with a LAN-reachable port.
 */
export async function ensureBootstrapAdmin(
  db: Db,
  config: Config,
  logger?: { warn: (obj: unknown, msg?: string) => void },
): Promise<BootstrapResult> {
  const users = new UserRepo(db);
  const existing = users.byUsername(config.adminUser);
  if (existing) return { created: false, username: existing.username };

  const generated = config.adminPassword === null;
  const password = config.adminPassword ?? generatedPassword();
  users.create({
    username: config.adminUser,
    displayName: 'Administrator',
    passwordHash: await hashPassword(password),
    role: 'admin',
    mustChangePw: generated,
  });

  if (generated && logger) {
    logger.warn(
      { username: config.adminUser, password },
      'bootstrap admin created with a generated password; sign in and change it, then remove this line from the log rotation',
    );
  }
  return { created: true, username: config.adminUser, generatedPassword: generated ? password : undefined };
}

/**
 * base64url of 24 random bytes: 192 bits, and every character is one the user can type without
 * an ambiguous-glyph lecture or a shell escaping problem.
 */
function generatedPassword(): string {
  return randomBytes(24).toString('base64url');
}
