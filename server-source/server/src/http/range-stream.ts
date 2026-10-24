import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import type { FastifyReply, FastifyRequest } from 'fastify';
import rangeParser from 'range-parser';

/**
 * Byte-range file delivery — the contract the Android downloader is built on.
 *
 * Everything here exists because of one failure mode: a resumed download that produces a *valid
 * looking* file made of mismatched bytes. That happens when the server answers a range request
 * with different content than the client already holds, and it cannot be detected on the device
 * afterwards, because the file's length and extension are exactly what was expected.
 *
 * Three rules prevent it:
 *   1. The ETag is derived from the content hash and is *strong*. A modification-time ETag changes
 *      when a file is touched but not changed, and stays the same when a file is replaced within
 *      the same second — both make If-Range lie.
 *   2. `If-Range` is honoured strictly. A client that sends a stale validator gets the whole file
 *      with 200, which its downloader will treat as "start over". Answering 206 to a mismatched
 *      If-Range is how a half-old, half-new file gets assembled.
 *   3. Nothing on this route ever sets `Content-Encoding`. A gzip filter would make byte offsets
 *      refer to compressed positions and silently corrupt every resumed segment.
 */

export interface RangeStreamOptions {
  /** Absolute path to the file to stream. */
  path: string;
  /** The filename offered to the client, already sanitized. */
  filename: string;
  /** Content hash; the ETag and the integrity header are both derived from it. */
  sha256: string;
  mediaType: string;
  /** Attachment for a download, inline for a reader or a WebView. */
  disposition?: 'attachment' | 'inline';
  /** Extra headers, e.g. the book id so a client can correlate a cached file. */
  extraHeaders?: Record<string, string>;
  /** Optional cap, so an operator can refuse to send a 2 GB PDF over a phone hotspot. */
  maxBytes?: number;
}

/** Strong validator: content-derived, quoted, and short enough to be a comfortable header. */
export function etagFor(sha256: string): string {
  return `"${sha256.slice(0, 32)}"`;
}

/**
 * RFC 6266 / 5987 filename. The ASCII fallback matters because some Android download managers
 * still ignore `filename*`, and a Chinese title without it arrives as `%E4%B8%89%E4%BD%93.epub`.
 */
export function contentDisposition(disposition: 'attachment' | 'inline', filename: string): string {
  const asciiFallback = filename
    .replace(/[^\x20-\x7e]/gu, '_')
    .replace(/["\\]/gu, '');
  const encoded = encodeURIComponent(filename);
  return `${disposition}; filename="${asciiFallback}"; filename*=UTF-8''${encoded}`;
}

export interface RangeDecision {
  status: 200 | 206 | 416;
  start: number;
  end: number;
  /** Header value for 206, or for 416 with the total size. */
  contentRange?: string;
}

/**
 * Decides what to send, given the request headers and the file's real size.
 *
 * Split out as a pure function so the range rules can be tested without a socket: this is exactly
 * the logic where an off-by-one produces a file that is one byte short and still opens.
 */
export function decideRange(input: {
  size: number;
  rangeHeader?: string | undefined;
  ifRangeHeader?: string | undefined;
  etag: string;
  ifNoneMatch?: string | undefined;
}): RangeDecision {
  const { size, rangeHeader, ifRangeHeader, etag } = input;

  // If-Range only applies when it matches. Any mismatch means "the client's copy is not this
  // file", and the only safe answer is the whole thing.
  const rangeUsable = ifRangeHeader === undefined || ifRangeHeader === etag;
  if (rangeHeader === undefined || rangeHeader.trim().length === 0 || !rangeUsable) {
    return { status: 200, start: 0, end: Math.max(0, size - 1) };
  }

  // The unit has to be checked here: range-parser accepts `items=0-9` and returns a byte range for
  // it, so a non-bytes unit would otherwise be honoured as if it were bytes.
  if (!/^\s*bytes\s*=/iu.test(rangeHeader)) {
    return { status: 200, start: 0, end: Math.max(0, size - 1) };
  }

  const parsed = rangeParser(size, rangeHeader, { combine: true });
  // These two codes are the opposite of the intuitive reading, and were established by probing the
  // installed version rather than from memory: -2 is a *malformed* header, which RFC 7233 says to
  // ignore (so the whole file), and -1 is a syntactically valid but *unsatisfiable* range, which is
  // the only case that earns a 416. Swapping them makes a broken client get a 416 for a typo and a
  // resuming client silently receive a file it cannot splice.
  if (parsed === -2) {
    return { status: 200, start: 0, end: Math.max(0, size - 1) };
  }
  if (parsed === -1 || parsed.length === 0) {
    return { status: 416, start: 0, end: -1, contentRange: `bytes */${size}` };
  }
  // More than one range means a multipart/byteranges response, which this server does not build.
  // Ignoring the header entirely is spec-legal and keeps every response single-part, which is what
  // the downloader relies on.
  if (parsed.length > 1) {
    return { status: 200, start: 0, end: Math.max(0, size - 1) };
  }

  const first = parsed[0];
  if (!first || first.start >= size) {
    return { status: 416, start: 0, end: -1, contentRange: `bytes */${size}` };
  }
  return { status: 206, start: first.start, end: first.end, contentRange: `bytes ${first.start}-${first.end}/${size}` };
}

/**
 * Streams the file, honoring ranges. Returns true when the response was sent, so callers do not
 * accidentally write a second body.
 */
export async function sendFileWithRanges(
  request: FastifyRequest,
  reply: FastifyReply,
  options: RangeStreamOptions,
): Promise<void> {
  const info = await stat(options.path);
  const size = info.size;
  const etag = etagFor(options.sha256);

  reply.header('Accept-Ranges', 'bytes');
  reply.header('ETag', etag);
  reply.header('Last-Modified', info.mtime.toUTCString());
  reply.header('Content-Type', options.mediaType);
  reply.header('X-Content-Sha256', options.sha256);
  reply.header('Content-Disposition', contentDisposition(options.disposition ?? 'attachment', options.filename));
  reply.header('Cache-Control', 'private, max-age=31536000, immutable');
  for (const [name, value] of Object.entries(options.extraHeaders ?? {})) reply.header(name, value);

  if (options.maxBytes !== undefined && size > options.maxBytes) {
    // Content-Type was already set to the file's own media type, and Fastify's serializer for a
    // non-JSON type accepts only a string or a Buffer — an object payload there is rejected as an
    // invalid payload type, which surfaces as a 500 from what should be a clean 413.
    reply.type('application/json');
    reply.code(413);
    await reply.send({
      error: { code: 'payload_too_large', message: 'File exceeds the configured delivery limit' },
    });
    return;
  }

  // An unconditional request that already holds this version needs nothing but the validator.
  if (request.headers['if-none-match'] === etag) {
    reply.code(304);
    await reply.send();
    return;
  }

  const decision = decideRange({
    size,
    rangeHeader: typeof request.headers.range === 'string' ? request.headers.range : undefined,
    ifRangeHeader: typeof request.headers['if-range'] === 'string' ? request.headers['if-range'] : undefined,
    etag,
    ifNoneMatch: typeof request.headers['if-none-match'] === 'string' ? request.headers['if-none-match'] : undefined,
  });

  if (decision.status === 416) {
    reply.header('Content-Range', decision.contentRange!);
    reply.type('application/json');
    reply.code(416);
    await reply.send({
      error: { code: 'range_not_satisfiable', message: 'Requested range is outside the file' },
    });
    return;
  }

  reply.header('Content-Length', String(decision.end - decision.start + 1));
  if (decision.status === 206) reply.header('Content-Range', decision.contentRange!);
  reply.code(decision.status);

  if (request.method === 'HEAD') {
    await reply.send();
    return;
  }

  const stream = createReadStream(options.path, {
    start: decision.start,
    end: decision.end,
    // 1 MiB: large enough that a 900 MB file is a few dozen syscalls, small enough that a dozen
    // concurrent readers do not each pin megabytes of buffer.
    highWaterMark: 1 << 20,
  });

  // Fastify owns the response from here: handing it the stream keeps every header set above
  // (including Content-Length and Content-Range) on the real response. Writing to `reply.raw`
  // instead would bypass `reply.header()` entirely, which silently drops the validators and the
  // Content-Range the downloader depends on.
  const onClose = (): void => {
    // A phone that changes networks mid-download otherwise leaves the read handle open until the
    // stream happens to be collected.
    stream.destroy();
  };
  request.raw.on('close', onClose);
  stream.on('close', () => request.raw.off('close', onClose));

  await reply.send(stream);
}
