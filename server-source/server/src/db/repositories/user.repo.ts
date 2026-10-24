import type { Db } from '../index.ts';
import { newId } from '../../ids.ts';

export type UserRole = 'admin' | 'user';
export type UserStatus = 'active' | 'disabled';

export interface UserRow {
  id: string;
  username: string;
  display_name: string;
  email: string | null;
  password_hash: string;
  role: UserRole;
  status: UserStatus;
  permissions_json: string;
  prefs_json: string;
  quota_bytes: number | null;
  token_version: number;
  failed_attempts: number;
  locked_until: string | null;
  must_change_pw: number;
  last_login_at: string | null;
  created_at: string;
  updated_at: string;
}

/** What a route is allowed to hand to a client. `password_hash` never leaves this module. */
export interface PublicUser {
  id: string;
  username: string;
  displayName: string;
  email: string | null;
  role: UserRole;
  status: UserStatus;
  quotaBytes: number | null;
  mustChangePassword: boolean;
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export function toPublicUser(row: UserRow): PublicUser {
  return {
    id: row.id,
    username: row.username,
    displayName: row.display_name,
    email: row.email,
    role: row.role,
    status: row.status,
    quotaBytes: row.quota_bytes,
    mustChangePassword: row.must_change_pw === 1,
    lastLoginAt: row.last_login_at,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

const COLUMNS = `id, username, display_name, email, password_hash, role, status,
                 permissions_json, prefs_json, quota_bytes, token_version, failed_attempts,
                 locked_until, must_change_pw, last_login_at, created_at, updated_at`;

export class UserRepo {
  constructor(private readonly db: Db) {}

  create(input: {
    username: string;
    displayName: string;
    passwordHash: string;
    role?: UserRole;
    email?: string | null;
    quotaBytes?: number | null;
    mustChangePw?: boolean;
    id?: string;
  }): UserRow {
    const now = new Date().toISOString();
    const id = input.id ?? newId();
    this.db.run(
      `INSERT INTO users (id, username, display_name, email, password_hash, role, status,
                          quota_bytes, must_change_pw, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)`,
      [
        id,
        input.username,
        input.displayName,
        input.email ?? null,
        input.passwordHash,
        input.role ?? 'user',
        input.quotaBytes ?? null,
        input.mustChangePw ? 1 : 0,
        now,
        now,
      ],
    );
    return this.requireById(id);
  }

  byId(id: string): UserRow | undefined {
    return this.db.get<UserRow>(`SELECT ${COLUMNS} FROM users WHERE id = ?`, [id]);
  }

  /**
   * Lookup by the name a human typed. `username` is declared COLLATE NOCASE, so this is the
   * only place the case-insensitivity decision is expressed.
   */
  byUsername(username: string): UserRow | undefined {
    return this.db.get<UserRow>(`SELECT ${COLUMNS} FROM users WHERE username = ?`, [username]);
  }

  byEmail(email: string): UserRow | undefined {
    return this.db.get<UserRow>(`SELECT ${COLUMNS} FROM users WHERE email = ?`, [email]);
  }

  requireById(id: string): UserRow {
    const row = this.byId(id);
    if (!row) throw new Error(`user ${id} vanished between write and read`);
    return row;
  }

  list(filter: { query?: string; role?: UserRole; status?: UserStatus; limit: number; offset: number }): {
    items: UserRow[];
    total: number;
  } {
    // Every comparison is a bound parameter; the only in-SQL text is the fixed fragment below.
    const where: string[] = [];
    const args: (string | number)[] = [];
    if (filter.query) {
      where.push('(username LIKE ? ESCAPE \'\\\' OR display_name LIKE ? ESCAPE \'\\\')');
      const like = `%${escapeLike(filter.query)}%`;
      args.push(like, like);
    }
    if (filter.role) {
      where.push('role = ?');
      args.push(filter.role);
    }
    if (filter.status) {
      where.push('status = ?');
      args.push(filter.status);
    }
    const clause = where.length ? ` WHERE ${where.join(' AND ')}` : '';
    const total =
      this.db.get<{ n: number }>(`SELECT count(*) AS n FROM users${clause}`, args)?.n ?? 0;
    const items = this.db.all<UserRow>(
      `SELECT ${COLUMNS} FROM users${clause} ORDER BY username ASC LIMIT ? OFFSET ?`,
      [...args, filter.limit, filter.offset],
    );
    return { items, total };
  }

  /**
   * Writes a hash and nothing else.
   *
   * It deliberately does *not* bump `token_version`: the transparent rehash-on-login path calls
   * this, and bumping there would revoke the session that was mid-issue — the user would be
   * signed out by a successful sign-in. Callers that genuinely mean "throw every session away"
   * say so with `bumpTokenVersion` or `DeviceRepo.revokeAllForUser`.
   */
  setPassword(id: string, passwordHash: string, options: { mustChangePassword?: boolean } = {}): void {
    this.db.run(
      'UPDATE users SET password_hash = ?, must_change_pw = ?, updated_at = ? WHERE id = ?',
      [passwordHash, options.mustChangePassword ? 1 : 0, new Date().toISOString(), id],
    );
  }

  /**
   * Bumping `token_version` is what makes a role change, a disable, or a password reset take
   * effect immediately: every access token carries the old version and fails verification
   * against the fresh row, without a revocation list to consult on each request.
   */
  bumpTokenVersion(id: string): void {
    this.db.run('UPDATE users SET token_version = token_version + 1, updated_at = ? WHERE id = ?', [
      new Date().toISOString(),
      id,
    ]);
  }

  setStatus(id: string, status: UserStatus): void {
    this.db.run('UPDATE users SET status = ?, token_version = token_version + 1, updated_at = ? WHERE id = ?', [
      status,
      new Date().toISOString(),
      id,
    ]);
  }

  /** Role changes bump the token version: an old token asserting `user` must not still be admin. */
  setRole(id: string, role: UserRole): void {
    this.db.run('UPDATE users SET role = ?, token_version = token_version + 1, updated_at = ? WHERE id = ?', [
      role,
      new Date().toISOString(),
      id,
    ]);
  }

  setMustChangePassword(id: string, mustChange: boolean): void {
    this.db.run('UPDATE users SET must_change_pw = ?, updated_at = ? WHERE id = ?', [
      mustChange ? 1 : 0,
      new Date().toISOString(),
      id,
    ]);
  }

  /**
   * Guards the last-administrator rule. `WHERE status = 'active'` is deliberate: a disabled admin
   * cannot rescue anyone, so counting them as protection would let the account system lock
   * itself out while still appearing safe.
   */
  countAdmins(): number {
    return (
      this.db.get<{ n: number }>("SELECT count(*) AS n FROM users WHERE role = 'admin' AND status = 'active'")
        ?.n ?? 0
    );
  }

  updateProfile(
    id: string,
    patch: { username?: string; displayName?: string; email?: string | null; prefsJson?: string },
  ): void {
    const sets: string[] = [];
    const args: (string | null)[] = [];
    if (patch.username !== undefined) {
      sets.push('username = ?');
      args.push(patch.username);
    }
    if (patch.displayName !== undefined) {
      sets.push('display_name = ?');
      args.push(patch.displayName);
    }
    if (patch.email !== undefined) {
      sets.push('email = ?');
      args.push(patch.email);
    }
    if (patch.prefsJson !== undefined) {
      sets.push('prefs_json = ?');
      args.push(patch.prefsJson);
    }
    if (sets.length === 0) return;
    this.db.run(`UPDATE users SET ${sets.join(', ')}, updated_at = ? WHERE id = ?`, [
      ...args,
      new Date().toISOString(),
      id,
    ]);
  }

  setPermissions(id: string, permissionsJson: string): void {
    this.db.run(
      'UPDATE users SET permissions_json = ?, token_version = token_version + 1, updated_at = ? WHERE id = ?',
      [permissionsJson, new Date().toISOString(), id],
    );
  }

  setQuota(id: string, quotaBytes: number | null): void {
    this.db.run('UPDATE users SET quota_bytes = ?, updated_at = ? WHERE id = ?', [
      quotaBytes,
      new Date().toISOString(),
      id,
    ]);
  }

  recordFailedLogin(id: string, lockUntil: string | null): void {
    this.db.run(
      'UPDATE users SET failed_attempts = failed_attempts + 1, locked_until = ?, updated_at = ? WHERE id = ?',
      [lockUntil, new Date().toISOString(), id],
    );
  }

  recordSuccessfulLogin(id: string): void {
    const now = new Date().toISOString();
    this.db.run(
      'UPDATE users SET failed_attempts = 0, locked_until = NULL, last_login_at = ?, updated_at = ? WHERE id = ?',
      [now, now, id],
    );
  }

  delete(id: string): void {
    this.db.run('DELETE FROM users WHERE id = ?', [id]);
  }

  count(): number {
    return this.db.get<{ n: number }>('SELECT count(*) AS n FROM users')?.n ?? 0;
  }
}

/** LIKE metacharacters in user input would otherwise turn "100%" into a prefix wildcard. */
export function escapeLike(value: string): string {
  return value.replace(/[\\%_]/g, (c) => `\\${c}`);
}
