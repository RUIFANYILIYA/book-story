import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parseWith, idSchema } from '../../http/validate.ts';
import { AppError } from '../../errors.ts';
import { createGuards } from '../../auth/guard.ts';
import { CAPABILITIES, capabilitiesFor, parseGrants, CapabilityGrantError } from '../../auth/capabilities.ts';
import { hashPassword } from '../../auth/password.ts';
import { toPublicUser, type UserRow, type UserRole, type UserStatus } from '../../db/repositories/user.repo.ts';
import { isUniqueViolation } from '../../db/index.ts';
import type { SessionService } from '../../auth/sessions.ts';

/**
 * Usernames are matched case-insensitively (the column is COLLATE NOCASE) and appear in logs and
 * Content-Disposition-adjacent text, so the alphabet is restricted to letters, digits and a few
 * separators — including CJK, which this deployment will realistically use.
 */
export const usernameSchema = z
  .string()
  .trim()
  .min(2, 'username must be at least 2 characters')
  .max(64, 'username must be at most 64 characters')
  .regex(/^[\p{L}\p{N}._@-]+$/u, 'username may contain letters, digits and . _ @ - only');

const passwordSchema = z.string().min(10, 'password must be at least 10 characters').max(1024);

const CreateUserBody = z.object({
  username: usernameSchema,
  displayName: z.string().trim().min(1).max(200),
  password: passwordSchema,
  role: z.enum(['admin', 'user']).default('user'),
  email: z.string().trim().toLowerCase().email().max(320).nullish(),
  quotaBytes: z.number().int().nonnegative().nullish(),
  permissions: z.record(z.string(), z.boolean()).optional(),
});

const PatchUserBody = z.object({
  displayName: z.string().trim().min(1).max(200).optional(),
  email: z.string().trim().toLowerCase().email().max(320).nullable().optional(),
  role: z.enum(['admin', 'user']).optional(),
  status: z.enum(['active', 'disabled']).optional(),
  quotaBytes: z.number().int().nonnegative().nullable().optional(),
  permissions: z.record(z.string(), z.boolean()).optional(),
  mustChangePassword: z.boolean().optional(),
});

const ResetPasswordBody = z.object({ newPassword: passwordSchema });

function grantsOrThrow(permissions: Record<string, boolean>): string {
  try {
    parseGrants(JSON.stringify(permissions));
  } catch (err) {
    if (err instanceof CapabilityGrantError) {
      throw new AppError('validation_failed', err.message, {
        details: { allowed: CAPABILITIES },
      });
    }
    throw err;
  }
  return JSON.stringify(permissions);
}

export function registerUserRoutes(app: FastifyInstance, deps: { session: SessionService }): void {
  const { session } = deps;
  const guards = createGuards(session);
  const manage = { preHandler: [guards.authenticate, guards.require('user:manage')] };

  app.get('/api/v1/users', manage, async (request) => {
    const query = parseWith(
      z.object({
        query: z.string().trim().max(200).optional(),
        role: z.enum(['admin', 'user']).optional(),
        status: z.enum(['active', 'disabled']).optional(),
        limit: z.coerce.number().int().min(1).max(200).default(50),
        offset: z.coerce.number().int().min(0).max(1_000_000).default(0),
      }),
      request.query,
      'query',
    );
    const { items, total } = session.users.list(query);
    return { items: items.map(toPublicUser), total, limit: query.limit, offset: query.offset };
  });

  app.post('/api/v1/users', manage, async (request, reply) => {
    const body = parseWith(CreateUserBody, request.body, 'body');
    const permissionsJson = body.permissions ? grantsOrThrow(body.permissions) : undefined;
    let created: UserRow;
    try {
      created = session.users.create({
        username: body.username,
        displayName: body.displayName,
        passwordHash: await hashPassword(body.password),
        role: body.role as UserRole,
        email: body.email ?? null,
        quotaBytes: body.quotaBytes ?? null,
        mustChangePw: true,
      });
    } catch (err) {
      if (isUniqueViolation(err)) {
        throw new AppError('already_exists', 'That username or email is taken', {
          details: { username: body.username },
        });
      }
      throw err;
    }
    if (permissionsJson) session.users.setPermissions(created.id, permissionsJson);
    // The plaintext is only ever returned here, once, over a channel the admin controls. There is
    // no email transport in this build, so "create and hand over" has to be explicit.
    return reply.status(201).send({
      user: toPublicUser(created),
      initialPassword: body.password,
      mustChangePassword: true,
    });
  });

  app.get('/api/v1/users/:id', manage, async (request) => {
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    const user = session.users.byId(id);
    if (!user) throw new AppError('user_not_found', 'User not found');
    return {
      user: toPublicUser(user),
      capabilities: capabilitiesFor(user),
      devices: session.devices.listForUser(id).length,
    };
  });

  app.patch('/api/v1/users/:id', manage, async (request) => {
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    const body = parseWith(PatchUserBody, request.body, 'body');
    const target = session.users.byId(id);
    if (!target) throw new AppError('user_not_found', 'User not found');

    // The last admin cannot be demoted or disabled by anyone, including themselves: every
    // recovery path in this server starts by signing in as an admin.
    const losingAdmin =
      target.role === 'admin' &&
      (body.status === 'disabled' || body.role === 'user') &&
      session.users.countAdmins() <= 1;
    if (losingAdmin) {
      throw new AppError('unprocessable', 'Refusing to remove the last administrator');
    }

    if (body.displayName !== undefined || body.email !== undefined) {
      session.users.updateProfile(id, {
        ...(body.displayName !== undefined ? { displayName: body.displayName } : {}),
        ...(body.email !== undefined ? { email: body.email } : {}),
      });
    }
    if (body.permissions !== undefined) session.users.setPermissions(id, grantsOrThrow(body.permissions));
    if (body.quotaBytes !== undefined) session.users.setQuota(id, body.quotaBytes);
    if (body.status !== undefined) session.users.setStatus(id, body.status as UserStatus);
    if (body.role !== undefined) session.users.setRole(id, body.role as UserRole);
    if (body.mustChangePassword !== undefined) {
      session.users.setMustChangePassword(id, body.mustChangePassword);
    }
    return { user: toPublicUser(session.users.requireById(id)) };
  });

  app.post('/api/v1/users/:id/password', manage, async (request, reply) => {
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    const body = parseWith(ResetPasswordBody, request.body, 'body');
    if (!session.users.byId(id)) throw new AppError('user_not_found', 'User not found');
    session.users.setPassword(id, await hashPassword(body.newPassword), { mustChangePassword: false });
    // A reset is normally a response to a compromise, so every live session goes with it.
    const revoked = session.devices.revokeAllForUser(id);
    return reply.status(200).send({ sessionsRevoked: revoked });
  });

  app.post('/api/v1/users/:id/tokens/revoke', manage, async (request) => {
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    if (!session.users.byId(id)) throw new AppError('user_not_found', 'User not found');
    return { sessionsRevoked: session.devices.revokeAllForUser(id) };
  });

  /**
   * Deleting a user removes their credentials and sessions but keeps the books they uploaded:
   * the library is shared and owned by nobody in particular, and destroying content because an
   * account closed would be a far worse default than leaving an uploader reference nulled out.
   */
  app.delete('/api/v1/users/:id', manage, async (request, reply) => {
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    const target = session.users.byId(id);
    if (!target) throw new AppError('user_not_found', 'User not found');
    if (target.role === 'admin' && session.users.countAdmins() <= 1) {
      throw new AppError('unprocessable', 'Refusing to delete the last administrator');
    }
    session.devices.revokeAllForUser(id);
    session.users.delete(id);
    return reply.status(204).send();
  });
}
