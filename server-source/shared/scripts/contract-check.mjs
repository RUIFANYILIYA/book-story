#!/usr/bin/env node
/**
 * Fails when the committed contract.json no longer matches openapi.yaml, or when the server's
 * error-code table and the documented `ErrorCode` enum have drifted apart.
 *
 * This is the cheap half of the drift gate; the expensive half (asserting every `required`
 * field is actually present in real responses) lives in server/test/contract.test.ts.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { buildManifest, serialize } from './gen-manifest.mjs';

const sharedDir = join(dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = join(sharedDir, '..');

const failures = [];

const yamlText = readFileSync(join(sharedDir, 'api-spec', 'openapi.yaml'), 'utf8');
const manifest = buildManifest(yamlText);
const expected = serialize(manifest);

let committed = null;
try {
  committed = readFileSync(join(sharedDir, 'api-spec', 'contract.json'), 'utf8');
} catch {
  failures.push('shared/api-spec/contract.json is missing — run `npm run contract:gen`.');
}

if (committed !== null && committed !== expected) {
  const committedObj = JSON.parse(committed);
  const documentedPaths = new Set(manifest.operations.map((o) => `${o.method} ${o.path}`));
  const committedPaths = new Set(committedObj.operations.map((o) => `${o.method} ${o.path}`));
  const added = [...documentedPaths].filter((p) => !committedPaths.has(p));
  const removed = [...committedPaths].filter((p) => !documentedPaths.has(p));
  if (added.length) failures.push(`operations added to the spec: ${added.join(', ')}`);
  if (removed.length) failures.push(`operations removed from the spec: ${removed.join(', ')}`);
  if (!added.length && !removed.length) {
    failures.push('field/enum-level change in openapi.yaml (schemas differ but paths match)');
  }
  if (!failures.some((f) => /run `npm run contract:gen`/.test(f))) {
    failures.push('committed contract.json is stale — run `npm run contract:gen` and commit it.');
  }
}

/** The server's own closed error-code set, read from the one file that defines it. */
function serverErrorCodes() {
  const source = readFileSync(join(repoRoot, 'server', 'src', 'errors.ts'), 'utf8');
  const block = /export const ErrorCodes = \{([\s\S]*?)\} as const/.exec(source);
  if (!block) throw new Error('could not locate ErrorCodes in server/src/errors.ts');
  return [...block[1].matchAll(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:/gm)].map((m) => m[1]);
}

const documentedCodes = manifest.schemas.ErrorCode?.enum;
if (!Array.isArray(documentedCodes)) {
  failures.push('openapi.yaml must define components.schemas.ErrorCode as a sorted enum.');
} else {
  const server = serverErrorCodes().sort();
  const spec = [...documentedCodes].sort();
  const undocumented = server.filter((c) => !spec.includes(c));
  const nonexistent = spec.filter((c) => !server.includes(c));
  if (undocumented.length) {
    failures.push(`server throws codes missing from the spec: ${undocumented.join(', ')}`);
  }
  if (nonexistent.length) {
    failures.push(`spec documents codes the server never emits: ${nonexistent.join(', ')}`);
  }
}

if (failures.length) {
  process.stderr.write(`contract check FAILED\n  - ${failures.join('\n  - ')}\n`);
  process.exit(1);
}

process.stdout.write(
  `contract check OK: ${manifest.operations.length} operations, ` +
    `${Object.keys(manifest.schemas).length} schemas, ${documentedCodes.length} error codes\n`,
);
