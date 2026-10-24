import type { Db } from '../db/index.ts';
import type { DeviceRow, DeviceDescriptor } from '../db/repositories/device.repo.ts';
import { DeviceRepo } from '../db/repositories/device.repo.ts';
import type { UserRow } from '../db/repositories/user.repo.ts';
import { UserRepo, toPublicUser, type PublicUser } from '../db/repositories/user.repo.ts';
import { AppError } from '../errors.ts';
import { hashPassword, needsRehash, newOpaqueToken, verifyPassword } from './password.ts';
import { capabilitiesFor, type Capability } from './capabilities.ts';
import type { TokenService } from './tokens.ts';

const MAX_FAILED_ATTEMPTS = 5;
const LOCK_MINUTES = 15;

export interface SessionGrant {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  refreshToken: string;
  deviceId: string;
  user: PublicUser;
}

export interface Authenticated {
  user: UserRow;
  device: DeviceRow;
  capabilities: Capability[];
}

export interface SessionServiceDeps {
  db: Db;
  tokens: TokenService;
  accessTtlSeconds: number;
  refreshIdleDays: number;
  refreshAbsoluteDays: number;
}

export class SessionService {
  readonly users: UserRepo;
  readonly devices: DeviceRepo;

  constructor(private readonly deps: SessionServiceDeps) {
    this.users = new UserRepo(deps.db);
    this.devices = new DeviceRepo(deps.db);
  }

  async login(input: {
    username: string;
    password: string;
    device?: DeviceDescriptor;
    ip?: string | null;
  }): Promise<SessionGrant> {
    const user = this.users.byUsername(input.username);

    // Always spend a hash, so "no such user" and "wrong password" are indistinguishable by
    // timing. Without this the username list of the library is one curl loop away.
    const candidate = user ?? (await this.dummyUser());
    const matches = await verifyPassword(input.password, candidate.password_hash);

    if (!user) {
      this.attempt(input, false, input.ip);
      throw new AppError('invalid_credentials', 'Incorrect username or password');
    }

    if (this.isLocked(user)) {
      this.attempt(input, false, input.ip);
      throw new AppError('account_locked', 'Too many failed attempts; try again later', {
        details: { lockedUntil: user.locked_until },
      });
    }

    if (!matches) {
      this.recordFailure(user, input.ip);
      throw new AppError('invalid_credentials', 'Incorrect username or password');
    }

    if (user.status !== 'active') {
      this.attempt(input, false, input.ip);
      throw new AppError('account_disabled', 'This account is disabled');
    }

    // `must_change_pw` is advisory, not a login gate. The only endpoint that can clear it
    // requires an authenticated caller, so blocking login here would make a freshly created
    // account — including the first admin on a new volume — permanently unreachable. It is
    // surfaced as `user.mustChangePassword` and prompted by the client instead.
    // Transparent upgrade when the stored parameters are behind the current ones. Cheap, and
    // the only moment we can do it without the plaintext.
    if (needsRehash(user.password_hash)) {
      this.users.setPassword(user.id, await hashPassword(input.password), { mustChangePassword: false });
    }

    this.users.recordSuccessfulLogin(user.id);
    this.attempt(input, true, input.ip);
    return this.issue(user, input.device, input.ip ?? null);
  }

  /**
   * Rotate on every use. A token that is present in the table but already revoked means
   * somebody is replaying a spent credential — either the client crashed mid-rotation or the
   * token was copied. Both cases end the whole lineage.
   */
  async refresh(input: { refreshToken: string; ip?: string | null }): Promise<SessionGrant> {
    const hash = this.devices.hashRefreshToken(input.refreshToken);
    const row = this.devices.byRefreshHash(hash);
    if (!row) {
      throw new AppError('invalid_refresh', 'Refresh token is not recognised');
    }
    if (row.revoked_at !== null) {
      const revoked = this.devices.revokeFamily(row.family_id);
      throw new AppError('token_reused', 'Refresh token was already used; this device was signed out', {
        details: { devicesRevoked: revoked },
      });
    }
    if (Date.parse(row.expires_at) <= Date.now()) {
      this.devices.revoke(row.id);
      throw new AppError('invalid_refresh', 'Session expired; sign in again');
    }

    const user = this.users.byId(row.user_id);
    if (!user || user.status !== 'active') {
      this.devices.revokeFamily(row.family_id);
      throw new AppError('invalid_refresh', 'Account is no longer permitted');
    }

    const next = newOpaqueToken();
    // The idle window slides; the absolute deadline does not. Without the cap a device that
    // refreshes weekly would hold a lineage open forever despite never being unlocked.
    const idleExpiry = Date.now() + this.deps.refreshIdleDays * 86_400_000;
    const bornAt = Date.parse(row.created_at);
    const absoluteExpiry = bornAt + this.deps.refreshAbsoluteDays * 86_400_000;
    const expiresAt = new Date(Math.min(idleExpiry, absoluteExpiry)).toISOString();

    const rotated = this.devices.rotate(row.id, next.hash, expiresAt);
    return this.grant(user, rotated, next.value);
  }

  async logout(refreshToken: string): Promise<void> {
    const hash = this.devices.hashRefreshToken(refreshToken);
    const row = this.devices.byRefreshHash(hash);
    // Idempotent by design: signing out of an already-dead session reports success, because the
    // caller's intent ("this credential must not work again") is satisfied either way.
    if (row) this.devices.revokeFamily(row.family_id);
  }

  /**
   * Guard path: verify the JWT, then re-read the user and device, because a token is a snapshot
   * and both can have moved since it was minted.
   */
  async authenticate(accessToken: string): Promise<Authenticated> {
    const claims = await this.deps.tokens.verify(accessToken);
    const user = this.users.byId(claims.sub);
    if (!user) throw new AppError('unauthorized', 'Account no longer exists');
    if (user.status !== 'active') throw new AppError('account_disabled', 'This account is disabled');
    if (user.token_version !== claims.ver) {
      throw new AppError('unauthorized', 'Session was revoked', { details: { reason: 'version' } });
    }
    const device = this.devices.byId(claims.did);
    if (!device || device.revoked_at !== null) {
      throw new AppError('unauthorized', 'Session was revoked', { details: { reason: 'device' } });
    }
    if (Date.parse(device.expires_at) <= Date.now()) {
      throw new AppError('unauthorized', 'Session expired', { details: { reason: 'expired' } });
    }
    return { user, device, capabilities: capabilitiesFor(user) };
  }

  private async issue(
    user: UserRow,
    descriptor: DeviceDescriptor | undefined,
    ip: string | null,
  ): Promise<SessionGrant> {
    const token = newOpaqueToken();
    const device = this.devices.create({
      userId: user.id,
      kind: descriptor?.id ? 'device' : 'session',
      descriptor,
      refreshHash: token.hash,
      ip,
      expiresAt: new Date(Date.now() + this.deps.refreshIdleDays * 86_400_000).toISOString(),
    });
    return this.grant(user, device, token.value);
  }

  private async grant(user: UserRow, device: DeviceRow, refreshToken: string): Promise<SessionGrant> {
    const capabilities = capabilitiesFor(user);
    const signed = await this.deps.tokens.sign({
      sub: user.id,
      did: device.id,
      role: user.role,
      ver: user.token_version,
      caps: capabilities,
    });
    return {
      accessToken: signed.token,
      tokenType: 'Bearer',
      expiresIn: signed.expiresInSeconds,
      refreshToken,
      deviceId: device.id,
      user: toPublicUser(user),
    };
  }

  private isLocked(user: UserRow): boolean {
    return user.locked_until !== null && Date.parse(user.locked_until) > Date.now();
  }

  private recordFailure(user: UserRow, ip: string | null | undefined): void {
    const nextAttempts = user.failed_attempts + 1;
    const lockUntil =
      nextAttempts >= MAX_FAILED_ATTEMPTS
        ? new Date(Date.now() + LOCK_MINUTES * 60_000).toISOString()
        : null;
    this.users.recordFailedLogin(user.id, lockUntil);
    this.attempt({ username: user.username }, false, ip ?? null);
  }

  private attempt(
    input: { username: string },
    ok: boolean,
    ip: string | null | undefined,
    userAgent?: string,
  ): void {
    this.deps.db.run(
      'INSERT INTO login_attempts (at, ip, username, ok, user_agent) VALUES (?, ?, ?, ?, ?)',
      [new Date().toISOString(), ip ?? null, input.username.slice(0, 200), ok ? 1 : 0, userAgent ?? null],
    );
  }

  /**
   * A stable fake row whose hash cost matches a real verification. Created once per process;
   * its password is random, so it can never be guessed.
   */
  private dummyUserPromise?: Promise<UserRow>;
  private async dummyUser(): Promise<UserRow> {
    this.dummyUserPromise ??= hashPassword(newOpaqueToken().value).then((hash) => ({
      id: 'nonexistent',
      username: 'nonexistent',
      display_name: 'nonexistent',
      email: null,
      password_hash: hash,
      role: 'user' as const,
      status: 'active' as const,
      permissions_json: '{}',
      prefs_json: '{}',
      quota_bytes: null,
      token_version: 1,
      failed_attempts: 0,
      locked_until: null,
      must_change_pw: 0,
      last_login_at: null,
      created_at: '1970-01-01T00:00:00.000Z',
      updated_at: '1970-01-01T00:00:00.000Z',
    }));
    return this.dummyUserPromise;
  }
}
