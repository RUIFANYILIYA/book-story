import type { FastifyReply, FastifyRequest } from 'fastify';
import { AppError } from '../errors.ts';
import type { Capability } from './capabilities.ts';
import type { Authenticated, SessionService } from './sessions.ts';

declare module 'fastify' {
  interface FastifyRequest {
    /** Populated by `guards().authenticate`; undefined on routes that do not require it. */
    auth?: Authenticated;
  }
}

export function bearerToken(request: FastifyRequest): string {
  const header = request.headers.authorization;
  if (typeof header !== 'string' || header.length === 0) {
    throw new AppError('unauthorized', 'Missing Authorization header');
  }
  const [scheme, value] = header.split(' ');
  if (!scheme || scheme.toLowerCase() !== 'bearer' || !value) {
    throw new AppError('unauthorized', 'Authorization header must be "Bearer <token>"');
  }
  return value;
}

/**
 * Route-level guards rather than a global hook, because `/health`, `/meta` and the static admin
 * page must answer without credentials and a global hook means an exclusion list that rots.
 *
 * `require` never re-reads the database: the capability set was resolved and version-checked by
 * `authenticate` moments earlier in the same request, so authorization is a set lookup.
 */
export function createGuards(session: SessionService) {
  const authenticate = async (request: FastifyRequest): Promise<void> => {
    request.auth = await session.authenticate(bearerToken(request));
  };

  return {
    authenticate,
    require(capability: Capability) {
      return async (request: FastifyRequest, _reply: FastifyReply): Promise<void> => {
        const auth = request.auth;
        if (!auth) {
          throw new AppError('unauthorized', 'Authentication required');
        }
        if (!auth.capabilities.includes(capability)) {
          throw new AppError('capability_denied', `This action requires ${capability}`, {
            details: { capability },
          });
        }
      };
    },
    /** The caller must be that user, or hold user:manage. Object-level check, not route-level. */
    requireSelfOrManage(userId: string) {
      return async (request: FastifyRequest): Promise<void> => {
        const auth = request.auth;
        if (!auth) throw new AppError('unauthorized', 'Authentication required');
        if (auth.user.id !== userId && !auth.capabilities.includes('user:manage')) {
          throw new AppError('forbidden', 'You may only read your own data');
        }
      };
    },
  };
}
