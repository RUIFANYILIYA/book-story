import Fastify, { type FastifyInstance } from 'fastify';
import { createHmac } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import multipart from '@fastify/multipart';
import rateLimit from '@fastify/rate-limit';
import sensible from '@fastify/sensible';
import staticFiles from '@fastify/static';
import type { Config } from './config.ts';
import type { Db } from './db/index.ts';
import { AppError, type ErrorCode } from './errors.ts';
import { SessionService } from './auth/sessions.ts';
import { TokenService } from './auth/tokens.ts';
import { registerAuthRoutes } from './routes/v1/auth.routes.ts';
import { registerBatchRoutes } from './routes/v1/batch.routes.ts';
import { registerBookRoutes } from './routes/v1/books.routes.ts';
import { registerFileRoutes } from './routes/v1/files.routes.ts';
import { registerSyncRoutes } from './routes/v1/sync.routes.ts';
import { registerSystemRoutes } from './routes/v1/system.routes.ts';
import { registerTaxonomyRoutes } from './routes/v1/taxonomy.routes.ts';
import { registerUploadRoutes } from './routes/v1/uploads.routes.ts';
import { registerUserRoutes } from './routes/v1/users.routes.ts';
import { CursorCodec } from './http/pagination.ts';
import { MAX_BATCH_SIZE } from './http/validate.ts';
import {
  API_VERSION,
  FEATURES,
  FORMATS,
  MIN_APP_VERSION_CODE,
  PROTOCOL_VERSION,
  SERVER_VERSION,
  buildRevision,
} from './version.ts';

export interface AppDeps {
  config: Config;
  /** Absent only in unit tests that exercise routing without storage. */
  db?: Db;
  instanceId?: string;
}

export const MetaSchema = z.object({
  apiVersion: z.string(),
  serverVersion: z.string(),
  revision: z.string().nullable(),
  protocol: z.number().int(),
  minAppVersionCode: z.number().int(),
  upgradeRequired: z.boolean(),
  serverTime: z.string(),
  features: z.record(z.string(), z.boolean()),
  formats: z.object({ accepted: z.array(z.string()), renderable: z.array(z.string()) }),
  limits: z.object({ maxUploadBytes: z.number().int(), maxBatchSize: z.number().int() }),
});

export type MetaResponse = z.infer<typeof MetaSchema>;

/**
 * One master key, purpose-separated subkeys.
 *
 * Using the master key directly for two jobs means a compromise of one becomes a compromise of
 * both, and there is no way to rotate one without the other. A domain-separated HKDF output is
 * three lines and removes the whole class of problem.
 */
function deriveCursorKey(masterKey: string): string {
  return createHmac('sha256', masterKey).update('ebookd/cursor/v1').digest('base64url');
}

/** HTTP status → wire error code, for failures raised by Fastify and its plugins. */
const STATUS_TO_CODE: Record<number, ErrorCode> = {
  400: 'bad_request',
  401: 'unauthorized',
  403: 'forbidden',
  404: 'not_found',
  405: 'method_not_allowed',
  409: 'conflict',
  413: 'payload_too_large',
  414: 'uri_too_long',
  415: 'unsupported_media_type',
  416: 'range_not_satisfiable',
  422: 'unprocessable',
  429: 'too_many_requests',
  503: 'storage_unavailable',
};

export function buildMeta(config: Config, appVersionCode?: number): MetaResponse {
  // App version codes start at 1, so an absent header is the only case that must not flag;
  // a literal 0 means "older than anything this server has ever supported".
  const callerTooOld = appVersionCode !== undefined && appVersionCode < MIN_APP_VERSION_CODE;
  return {
    apiVersion: API_VERSION,
    serverVersion: SERVER_VERSION,
    revision: buildRevision(),
    protocol: PROTOCOL_VERSION,
    minAppVersionCode: MIN_APP_VERSION_CODE,
    upgradeRequired: callerTooOld,
    serverTime: new Date().toISOString(),
    features: {
      ...FEATURES,
      // Advertised truthfully rather than as a static constant.
      allowRegistration: config.allowRegistration,
    },
    formats: { accepted: [...FORMATS.accepted], renderable: [...FORMATS.renderable] },
    limits: { maxUploadBytes: config.maxUploadBytes, maxBatchSize: MAX_BATCH_SIZE },
  };
}

export async function buildApp(deps: AppDeps): Promise<FastifyInstance> {
  const { config, db, instanceId } = deps;

  const app = Fastify({
    logger: config.logLevel === 'silent' ? false : { level: config.logLevel },
    // A 512 MB upload arrives as one request body; Fastify's own limit must not undercut it.
    bodyLimit: config.maxUploadBytes,
    trustProxy: config.trustProxy,
    genReqId: (req) => (req.headers['x-request-id'] as string | undefined) ?? crypto.randomUUID(),
  });

  await app.register(sensible);
  await app.register(rateLimit, {
    global: true,
    max: 300,
    timeWindow: '1 minute',
    // The meta endpoint is polled by every app start; keep it cheap but still bounded.
  });
  await app.register(helmet, {
    contentSecurityPolicy: false,
    // The admin UI is served from this same origin and needs to inline a small amount of
    // bootstrap state; CSP gets written properly in M6 once the page exists.
  });
  if (config.corsOrigins.length > 0) {
    await app.register(cors, { origin: config.corsOrigins, credentials: true, maxAge: 600 });
  }
  await app.register(multipart, {
    limits: {
      fileSize: config.maxUploadBytes,
      files: 1,
      // Field names/values are read before the file part and must not be a way to spend memory.
      fields: 16,
      fieldSize: 64 * 1024,
    },
  });

  // The admin console is served from the same origin as the API it calls, so a browser session
  // needs no CORS entry and the tokens never leave the origin. A build step would put a toolchain
  // between the operator and the thing they are debugging on a NAS.
  await app.register(staticFiles, {
    root: fileURLToPath(new URL('../public/', import.meta.url)),
    prefix: '/admin/',
    index: ['index.html'],
    // Files here are hand-authored and change only with a deploy; a long cache would make an
    // operator stare at a stale console after upgrading.
    //
    // `false` (send no header) is not the same as "do not cache": with no Cache-Control a browser
    // is free to reuse the file heuristically, which is exactly how "I changed the console and the
    // browser still shows the old one" happens. `no-cache` means "revalidate every time", and the
    // ETag turns that into a 304 when nothing changed — cheap, and impossible to be stale.
    cacheControl: false,
    etag: true,
    setHeaders: (res) => {
      res.header('cache-control', 'no-cache');
    },
  });
  app.get('/admin', async (_request, reply) => reply.redirect('/admin/', 302));

  // The bare origin used to answer with a JSON 404, which reads as "the server is broken" to
  // anyone who typed the host and port straight out of the startup log. The API lives under
  // /api/v1 and has nothing to serve at the root, so send people to the console instead.
  app.get('/', async (_request, reply) => reply.redirect('/admin/', 302));

  app.setErrorHandler((err, request, reply) => {
    const requestId = request.id;

    if (err instanceof AppError) {
      void reply.status(err.status).send({
        error: {
          code: err.code,
          message: err.message,
          ...(err.details ? { details: err.details } : {}),
          requestId,
        },
      });
      return;
    }

    // Anything below is a plain Error thrown by Fastify or its plugins (@fastify/multipart
    // throws FST_REQ_BODY_TOO_LARGE, the validator throws FST_ERR_VALIDATION, rate-limit 429).
    const fe = err as {
      statusCode?: number;
      code?: string;
      message?: string;
      validation?: unknown;
      validationContext?: string;
    };
    const status = typeof fe.statusCode === 'number' ? fe.statusCode : 500;
    const message = fe.message ?? 'Request failed';

    if (status >= 500) {
      request.log.error({ err }, 'unhandled error');
      void reply
        .status(500)
        .send({ error: { code: 'internal', message: 'Internal server error', requestId } });
      return;
    }

    const isValidation =
      fe.validation !== undefined ||
      fe.validationContext !== undefined ||
      fe.code === 'FST_ERR_VALIDATION';
    const code: ErrorCode = isValidation
      ? 'validation_failed'
      : (STATUS_TO_CODE[status] ?? 'bad_request');

    void reply.status(status).send({ error: { code, message, requestId } });
  });

  // Unmatched URLs never reach setErrorHandler (nothing is thrown), so the envelope has to be
  // produced here too — otherwise clients get Fastify's ad-hoc {message,error,statusCode} shape.
  app.setNotFoundHandler((request, reply) => {
    void reply.status(404).send({
      error: {
        code: 'not_found' as ErrorCode,
        message: `Route ${request.method}:${request.url} not found`,
        requestId: request.id,
      },
    });
  });

  app.get('/health', async () => ({
    status: 'ok',
    uptimeSeconds: Math.round(process.uptime()),
  }));

  // Readiness means the schema is installed and answerable, so a container that started while
  // its volume was still mounting reports 503 instead of serving 500s from every route.
  app.get('/ready', async () => {
    if (!db) {
      throw new AppError('storage_unavailable', 'database is not open yet');
    }
    db.get('SELECT 1');
    const state = db.get<{ instance_id: string; schema_version: number }>(
      'SELECT instance_id, schema_version FROM app_state WHERE id = 1',
    );
    if (!state) {
      throw new AppError('storage_unavailable', 'schema is not installed');
    }
    return {
      status: 'ok',
      version: SERVER_VERSION,
      instanceId: instanceId ?? state.instance_id,
      schemaVersion: state.schema_version,
    };
  });

  const metaRoute = async (request: { headers: Record<string, unknown> }) => {
    const header = request.headers['x-app-version-code'];
    const code = typeof header === 'string' ? Number.parseInt(header, 10) : undefined;
    return buildMeta(config, Number.isNaN(code) ? undefined : code);
  };

  if (db) {
    const session = new SessionService({
      db,
      tokens: new TokenService(config.jwtSecret, config.accessTtlSeconds),
      accessTtlSeconds: config.accessTtlSeconds,
      refreshIdleDays: config.refreshIdleDays,
      refreshAbsoluteDays: config.refreshAbsoluteDays,
    });
    registerAuthRoutes(app, { session, config });
    registerUserRoutes(app, { session });
    registerUploadRoutes(app, { db, config, session });
    // Cursors carry sort-key values, so they are signed with a key derived from the master key
    // rather than the JWT secret: the two are revoked on different timelines and a leaked cursor
    // must not be able to mint a token, or vice versa.
    const codec = new CursorCodec(deriveCursorKey(config.masterKey));
    registerBookRoutes(app, { db, config, codec, session });
    registerFileRoutes(app, { db, config, session });
    registerTaxonomyRoutes(app, { db, session });
    registerBatchRoutes(app, { db, session });
    registerSyncRoutes(app, { db, session });
    registerSystemRoutes(app, { db, session });
  }

  // Response serialization is the only place a zod schema is handed to Fastify, and it goes
  // across as plain JSON Schema rather than through a type-provider: see http/validate.ts.
  app.get(
    '/api/v1/meta',
    { schema: { response: { 200: z.toJSONSchema(MetaSchema, { io: 'output', target: 'draft-07' }) } } },
    metaRoute,
  );
  // Unversioned alias: a reverse proxy that strips /api/v1 still answers discovery.
  app.get('/meta', metaRoute);

  return app;
}
