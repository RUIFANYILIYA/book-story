/**
 * Stable, closed set of machine-readable error codes.
 *
 * This is wire contract: the Android app branches on these, so values may be added but never
 * renamed or removed inside /api/v1. `shared/api-spec/openapi.yaml` documents the same list.
 */
export const ErrorCodes = {
  bad_request: 400,
  unauthorized: 401,
  invalid_credentials: 401,
  invalid_refresh: 401,
  token_reused: 401,
  forbidden: 403,
  capability_denied: 403,
  account_disabled: 403,
  account_locked: 429,
  not_found: 404,
  book_not_found: 404,
  file_not_found: 404,
  user_not_found: 404,
  collection_not_found: 404,
  category_not_found: 404,
  method_not_allowed: 405,
  conflict: 409,
  duplicate_content: 409,
  duplicate_book: 409,
  /** A file with this name is already shelved; the caller must say which one wins. */
  filename_conflict: 409,
  already_exists: 409,
  revision_conflict: 409,
  unsupported_media_type: 415,
  range_not_satisfiable: 416,
  payload_too_large: 413,
  uri_too_long: 414,
  unprocessable: 422,
  validation_failed: 422,
  too_many_requests: 429,
  insufficient_storage: 507,
  upgrade_required: 426,
  internal: 500,
  storage_unavailable: 503,
} as const satisfies Record<string, number>;

export type ErrorCode = keyof typeof ErrorCodes;

export class AppError extends Error {
  readonly code: ErrorCode;
  readonly status: number;
  readonly details: Record<string, unknown> | undefined;
  readonly expose: boolean;

  constructor(
    code: ErrorCode,
    message: string,
    options: { details?: Record<string, unknown>; cause?: unknown; expose?: boolean } = {},
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = 'AppError';
    this.code = code;
    this.status = ErrorCodes[code];
    this.details = options.details;
    this.expose = options.expose ?? this.status < 500;
  }

  static badRequest(message: string, details?: Record<string, unknown>): AppError {
    return new AppError('bad_request', message, { details });
  }
  static notFound(message = 'Resource not found'): AppError {
    return new AppError('not_found', message);
  }
  static conflict(code: ErrorCode, message: string, details?: Record<string, unknown>): AppError {
    return new AppError(code, message, { details });
  }
}

/** Shape of every non-2xx body. `{ data }` on success is deliberately absent — no envelope. */
export interface ErrorBody {
  error: {
    code: ErrorCode;
    message: string;
    details?: Record<string, unknown>;
    requestId?: string;
  };
}
