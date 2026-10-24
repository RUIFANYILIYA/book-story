import { z } from 'zod';
import { AppError } from '../errors.ts';

/**
 * One validation mechanism, applied in the handler.
 *
 * There is deliberately no route-schema/type-provider layer: `shared/api-spec/openapi.yaml` is
 * the source of truth for the wire format and `test/contract.test.ts` asserts the server honors
 * it, so a second, generated-from-code description would either drift from the spec or be
 * redundant. Parsing here keeps the failure mode explicit (`validation_failed` with a path for
 * every offending field) and keeps the dependency list short enough to build on a NAS.
 */
export function parseWith<T extends z.ZodType>(schema: T, value: unknown, where: string): z.infer<T> {
  const result = schema.safeParse(value);
  if (!result.success) {
    throw new AppError('validation_failed', `${where} did not match the expected shape`, {
      details: {
        where,
        issues: result.error.issues.slice(0, 20).map((issue) => ({
          path: issue.path.join('.') || where,
          message: issue.message,
          code: issue.code,
        })),
      },
    });
  }
  return result.data;
}

/** Query parameters arrive as strings; this is where "true"/"1"/"" become real types. */
export const csvList = (max = 20) =>
  z
    .string()
    .transform((v) => v.split(',').map((s) => s.trim()).filter((s) => s.length > 0))
    .pipe(z.array(z.string().min(1)).max(max));

export const booleanQuery = z
  .union([z.literal('true'), z.literal('false'), z.literal('1'), z.literal('0')])
  .transform((v) => v === 'true' || v === '1');

/** A client-generated id (bookmarks, download jobs) must be well-formed before it reaches SQL. */
export const idSchema = z
  .string()
  .min(26, 'id must be a 26-character identifier')
  .max(26, 'id must be a 26-character identifier')
  .regex(/^[0-9A-HJKMNP-TV-Z]{26}$/, 'id must use the Crockford base32 alphabet');

export const idListSchema = z.array(idSchema).min(1).max(2000);

/**
 * Largest `bookIds[]` any batch endpoint accepts; mirrored by the app's selection cap.
 *
 * Declared here rather than in app.ts because the route modules need it and app.ts imports the
 * route modules — putting it in either half of that pair would make the import a cycle.
 */
export const MAX_BATCH_SIZE = 2000;
