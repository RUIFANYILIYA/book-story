import type { Db } from '../index.ts';
import { newId } from '../../ids.ts';
import { sha256Hex } from '../../auth/password.ts';

export type DeviceKind = 'session' | 'device' | 'api';

export interface DeviceRow {
  id: string;
  user_id: string;
  kind: DeviceKind;
  label: string | null;
  platform: string | null;
  client_info_json: string;
  refresh_hash: string;
  family_id: string;
  rotated_from_id: string | null;
  scopes_json: string;
  created_ip: string | null;
  last_ip: string | null;
  created_at: string;
  last_used_at: string | null;
  expires_at: string;
  revoked_at: string | null;
}

export interface DeviceDescriptor {
  id?: string;
  label?: string | null;
  platform?: string | null;
  clientVersion?: string | null;
}

const COLUMNS = `id, user_id, kind, label, platform, client_info_json, refresh_hash, family_id,
                 rotated_from_id, scopes_json, created_ip, last_ip, created_at, last_used_at,
                 expires_at, revoked_at`;

export class DeviceRepo {
  constructor(private readonly db: Db) {}

  /**
   * Creates the row for a freshly issued refresh token.
   *
   * Rotation writes a *new* row rather than updating in place, and that is what makes theft
   * detectable at all: a replayed, already-rotated token still exists in the table as a revoked
   * row, so presenting it identifies the family to revoke. An update-in-place model can only
   * say "unknown token", which is indistinguishable from a forgery and revokes nothing.
   * The churn is bounded by the sweeper, which drops superseded rows past the grace window.
   */
  create(input: {
    userId: string;
    kind: DeviceKind;
    descriptor?: DeviceDescriptor;
    refreshHash: string;
    familyId?: string;
    rotatedFromId?: string | null;
    scopes?: string[];
    ip?: string | null;
    expiresAt: string;
  }): DeviceRow {
    const id = input.descriptor?.id ?? newId();
    const now = new Date().toISOString();
    this.db.run(
      `INSERT INTO auth_devices (id, user_id, kind, label, platform, client_info_json, refresh_hash,
                                 family_id, rotated_from_id, scopes_json, created_ip, created_at,
                                 last_used_at, expires_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        id,
        input.userId,
        input.kind,
        input.descriptor?.label ?? null,
        input.descriptor?.platform ?? null,
        JSON.stringify({ clientVersion: input.descriptor?.clientVersion ?? null }),
        input.refreshHash,
        input.familyId ?? id,
        input.rotatedFromId ?? null,
        JSON.stringify(input.scopes ?? ['*']),
        input.ip ?? null,
        now,
        now,
        input.expiresAt,
      ],
    );
    return this.require(id);
  }

  byId(id: string): DeviceRow | undefined {
    return this.db.get<DeviceRow>(`SELECT ${COLUMNS} FROM auth_devices WHERE id = ?`, [id]);
  }

  require(id: string): DeviceRow {
    const row = this.byId(id);
    if (!row) throw new Error(`device ${id} not found`);
    return row;
  }

  /** The lookup the refresh path runs. `refresh_hash` is unique, so this is the whole check. */
  byRefreshHash(hash: string): DeviceRow | undefined {
    return this.db.get<DeviceRow>(`SELECT ${COLUMNS} FROM auth_devices WHERE refresh_hash = ?`, [hash]);
  }

  listForUser(userId: string): DeviceRow[] {
    return this.db.all<DeviceRow>(
      `SELECT ${COLUMNS} FROM auth_devices
       WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ?
       ORDER BY last_used_at DESC, created_at DESC`,
      [userId, new Date().toISOString()],
    );
  }

  rotate(fromId: string, toRefreshHash: string, expiresAt: string): DeviceRow {
    const source = this.require(fromId);
    return this.db.tx(() => {
      this.revoke(fromId);
      return this.create({
        userId: source.user_id,
        kind: source.kind,
        descriptor: { label: source.label, platform: source.platform, id: undefined },
        refreshHash: toRefreshHash,
        familyId: source.family_id,
        rotatedFromId: source.id,
        scopes: JSON.parse(source.scopes_json) as string[],
        ip: source.last_ip,
        expiresAt,
      });
    });
  }

  revoke(id: string): void {
    this.db.run('UPDATE auth_devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL', [
      new Date().toISOString(),
      id,
    ]);
  }

  /**
   * The theft signal: a token that exists but was already rotated. Everything descended from the
   * same login is dropped at once, including whichever row the attacker is currently holding.
   */
  revokeFamily(familyId: string): number {
    const result = this.db.run(
      'UPDATE auth_devices SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL',
      [new Date().toISOString(), familyId],
    );
    return result.changes;
  }

  revokeAllForUser(userId: string, exceptDeviceId?: string): number {
    const result = exceptDeviceId
      ? this.db.run(
          'UPDATE auth_devices SET revoked_at = ? WHERE user_id = ? AND id <> ? AND revoked_at IS NULL',
          [new Date().toISOString(), userId, exceptDeviceId],
        )
      : this.db.run('UPDATE auth_devices SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL', [
          new Date().toISOString(),
          userId,
        ]);
    return result.changes;
  }

  touch(id: string, ip: string | null): void {
    this.db.run('UPDATE auth_devices SET last_used_at = ?, last_ip = ? WHERE id = ?', [
      new Date().toISOString(),
      ip,
      id,
    ]);
  }

  hashRefreshToken(value: string): string {
    return sha256Hex(value);
  }
}
