import { SignJWT, jwtVerify, type JWTPayload } from 'jose';
import { AppError } from '../errors.ts';
import type { Capability } from './capabilities.ts';

/**
 * Short-lived access tokens, HS256 over a single shared secret.
 *
 * The claims are deliberately minimal and each one earns its place:
 *   `ver` lets a password change, role change or disable revoke every live token without a
 *       blacklist that would have to be read on each request.
 *   `did` binds a token to the device row, so refresh rotation and per-device revocation have
 *       something to point at.
 *   `caps` is the resolved capability set, so the guard is a set lookup rather than a query.
 * Roles are *not* enough to drop caps here: the request path must not need a database round trip
 * to decide an authorization question that was already answered at login.
 */

const ISSUER = 'ebookd';

export interface AccessTokenClaims {
  sub: string;
  did: string;
  role: 'admin' | 'user';
  ver: number;
  caps: Capability[];
}

export interface IssuedToken {
  token: string;
  expiresAt: string;
  expiresInSeconds: number;
}

export class TokenService {
  readonly #key: Uint8Array;

  constructor(secret: string, private readonly ttlSeconds: number) {
    if (secret.length < 32) {
      throw new AppError('internal', 'EBOOK_JWT_SECRET must be at least 32 characters');
    }
    this.#key = new TextEncoder().encode(secret);
  }

  async sign(claims: AccessTokenClaims): Promise<IssuedToken> {
    const expiresInSeconds = this.ttlSeconds;
    const token = await new SignJWT({ ...claims })
      .setProtectedHeader({ alg: 'HS256', typ: 'JWT' })
      .setIssuer(ISSUER)
      .setSubject(claims.sub)
      .setIssuedAt()
      .setExpirationTime(`${expiresInSeconds}s`)
      .sign(this.#key);
    return {
      token,
      expiresAt: new Date(Date.now() + expiresInSeconds * 1000).toISOString(),
      expiresInSeconds,
    };
  }

  /** Verifies signature and lifetime only. Anything that needs fresh state goes through the guard. */
  async verify(token: string): Promise<AccessTokenClaims> {
    let payload: JWTPayload;
    try {
      ({ payload } = await jwtVerify(token, this.#key, { issuer: ISSUER }));
    } catch (err) {
      const message = err instanceof Error ? err.message : '';
      const reason = /expired/i.test(message) ? 'expired' : 'malformed';
      // One code for both cases: an expired token and a forged one must not be distinguishable
      // to the caller, or the endpoint becomes a token-validity oracle.
      throw new AppError('unauthorized', 'token is not valid', { details: { reason } });
    }
    const { sub, did, role, ver, caps } = payload as JWTPayload & Partial<AccessTokenClaims>;
    if (typeof sub !== 'string' || typeof did !== 'string' || typeof ver !== 'number') {
      throw new AppError('unauthorized', 'token is malformed', { details: { reason: 'claims' } });
    }
    if (role !== 'admin' && role !== 'user') {
      throw new AppError('unauthorized', 'token is malformed', { details: { reason: 'claims' } });
    }
    return {
      sub,
      did,
      role,
      ver,
      caps: Array.isArray(caps) ? (caps as Capability[]) : [],
    };
  }
}
