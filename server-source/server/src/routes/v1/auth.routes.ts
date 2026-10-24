import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parseWith, idSchema } from '../../http/validate.ts';
import { AppError } from '../../errors.ts';
import { createGuards } from '../../auth/guard.ts';
import { hashPassword, verifyPassword } from '../../auth/password.ts';
import type { SessionService } from '../../auth/sessions.ts';
import { toPublicUser } from '../../db/repositories/user.repo.ts';
import { usernameSchema } from './users.routes.ts';
import type { Config } from '../../config.ts';

const platformSchema = z.enum(['android', 'ios', 'web', 'cli', 'other']).optional();

const LoginBody = z.object({
  username: z.string().trim().min(1).max(200),
  password: z.string().min(1).max(1024),
  device: z
    .object({
      id: idSchema.optional(),
      label: z.string().trim().max(200).nullish(),
      platform: platformSchema,
      clientVersion: z.string().trim().max(100).nullish(),
    })
    .optional(),
});

const RefreshBody = z.object({ refreshToken: z.string().min(16).max(200) });

const LogoutBody = z.object({ refreshToken: z.string().min(16).max(200) });

const ChangePasswordBody = z.object({
  currentPassword: z.string().min(1).max(1024),
  newPassword: z.string().min(10, 'use at least 10 characters').max(1024),
});

const ProfileBody = z.object({
  username: usernameSchema.optional(),
  displayName: z.string().trim().min(1).max(200).optional(),
  email: z.string().trim().toLowerCase().email().max(320).nullable().optional(),
  prefs: z.record(z.string(), z.unknown()).optional(),
});

export interface AuthRouteDeps {
  session: SessionService;
  config: Config;
}

export function registerAuthRoutes(app: FastifyInstance, deps: AuthRouteDeps): void {
  const { session, config } = deps;
  const guards = createGuards(session);

  app.post('/api/v1/auth/login', async (request, reply) => {
    const body = parseWith(LoginBody, request.body, 'body');
    const grant = await session.login({
      username: body.username,
      password: body.password,
      device: body.device,
      ip: request.ip,
    });
    return reply.status(200).send(grant);
  });

  app.post('/api/v1/auth/refresh', async (request) => {
    const body = parseWith(RefreshBody, request.body, 'body');
    return session.refresh({ refreshToken: body.refreshToken, ip: request.ip });
  });

  app.post('/api/v1/auth/logout', async (request, reply) => {
    const body = parseWith(LogoutBody, request.body, 'body');
    await session.logout(body.refreshToken);
    return reply.status(204).send();
  });

  app.get('/api/v1/auth/me', { preHandler: [guards.authenticate] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    return {
      user: toPublicUser(auth.user),
      capabilities: auth.capabilities,
      deviceId: auth.device.id,
      storageQuotaBytes: auth.user.quota_bytes,
      serverTime: new Date().toISOString(),
      allowRegistration: config.allowRegistration,
    };
  });

  app.patch('/api/v1/auth/me', { preHandler: [guards.authenticate] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const body = parseWith(ProfileBody, request.body, 'body');

    const patch: { username?: string; displayName?: string; email?: string | null; prefsJson?: string } = {
      ...(body.displayName !== undefined ? { displayName: body.displayName } : {}),
      ...(body.email !== undefined ? { email: body.email } : {}),
      ...(body.prefs !== undefined ? { prefsJson: JSON.stringify(body.prefs) } : {}),
    };

    // The username is the login key, so renaming is the one profile change that can collide with
    // somebody else. Sessions survive it because tokens carry the user id, not the name.
    if (body.username !== undefined && body.username.toLowerCase() !== auth.user.username.toLowerCase()) {
      if (session.users.byUsername(body.username)) {
        throw AppError.conflict('already_exists', 'That username is taken', { username: body.username });
      }
      patch.username = body.username;
    }

    session.users.updateProfile(auth.user.id, patch);
    return { user: toPublicUser(session.users.requireById(auth.user.id)) };
  });

  /**
   * Changing a password invalidates every *other* session, leaving the caller's own device
   * usable. Revoking all including current would bounce the user to the sign-in screen the
   * moment they saved, which reads as a bug and trains people to not change passwords.
   */
  app.post('/api/v1/auth/me/password', { preHandler: [guards.authenticate] }, async (request, reply) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const body = parseWith(ChangePasswordBody, request.body, 'body');
    if (!(await verifyPassword(body.currentPassword, auth.user.password_hash))) {
      throw new AppError('invalid_credentials', 'Current password is incorrect');
    }
    if (body.currentPassword === body.newPassword) {
      throw new AppError('unprocessable', 'The new password must differ from the current one');
    }
    session.users.setPassword(auth.user.id, await hashPassword(body.newPassword), {
      mustChangePassword: false,
    });
    const revoked = session.devices.revokeAllForUser(auth.user.id, auth.device.id);
    return reply.status(200).send({ otherSessionsRevoked: revoked });
  });

  app.get('/api/v1/auth/devices', { preHandler: [guards.authenticate] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const items = session.devices.listForUser(auth.user.id).map((device) => ({
      id: device.id,
      kind: device.kind,
      label: device.label,
      platform: device.platform,
      current: device.id === auth.device.id,
      createdAt: device.created_at,
      lastUsedAt: device.last_used_at,
      expiresAt: device.expires_at,
    }));
    return { items };
  });

  app.delete('/api/v1/auth/devices/:id', { preHandler: [guards.authenticate] }, async (request, reply) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    const device = session.devices.byId(id);
    // 404 rather than 403 for someone else's device: confirming existence of a foreign device id
    // is an information leak, and the caller has no legitimate reason to distinguish.
    if (!device || device.user_id !== auth.user.id) {
      throw new AppError('not_found', 'Device not found');
    }
    session.devices.revokeFamily(device.family_id);
    return reply.status(204).send();
  });
}
