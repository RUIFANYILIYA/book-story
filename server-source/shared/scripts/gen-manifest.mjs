#!/usr/bin/env node
/**
 * Derives the normalized wire manifest from shared/api-spec/openapi.yaml.
 *
 * The generated file is committed on purpose: reviewing a diff of contract.json is how a
 * breaking change to the Android contract gets caught in review instead of at runtime on a
 * phone that already shipped. Output is byte-stable (sorted keys, no timestamps) so the only
 * diffs you see are real ones.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join } from 'node:path';
import { parse } from 'yaml';

const here = dirname(fileURLToPath(import.meta.url));
const specPath = join(here, '..', 'api-spec', 'openapi.yaml');
const outPath = join(here, '..', 'api-spec', 'contract.json');

const METHOD_VERBS = ['get', 'put', 'post', 'patch', 'delete', 'head', 'options'];

function localRef(name) {
  return typeof name === 'string' && name.startsWith('#/');
}

/** Property names whose value must be one of a closed set — the client branches on these. */
function enumOf(schema) {
  if (!schema || typeof schema !== 'object') return undefined;
  if (Array.isArray(schema.enum)) return [...schema.enum].sort();
  if (schema.const !== undefined) return [schema.const];
  return undefined;
}

function describeSchema(name, schema) {
  if (!schema || typeof schema !== 'object') return null;
  const required = Array.isArray(schema.required) ? [...schema.required].sort() : [];
  const properties = {};
  for (const [prop, def] of Object.entries(schema.properties ?? {})) {
    properties[prop] = {
      type: Array.isArray(def.type) ? [...def.type].sort().join('|') : (def.type ?? 'ref'),
      enum: enumOf(def) ?? (def.items ? enumOf(def.items) : undefined),
      nullable: def.nullable === true || (Array.isArray(def.type) && def.type.includes('null')),
    };
  }
  return {
    name,
    type: schema.type ?? 'object',
    required,
    additionalProperties:
      schema.additionalProperties === undefined ? 'unset' : schema.additionalProperties,
    enum: enumOf(schema),
    properties,
  };
}

/**
 * Every local `$ref` must resolve to something in the document.
 *
 * A dangling reference is otherwise invisible: the YAML still parses, code generators emit an
 * `any`/`object` for that shape, and the first person to discover it is the app at runtime.
 * Catching it here costs nothing because the whole document is already in memory.
 */
function assertRefsResolve(spec) {
  const missing = [];
  const walk = (node, at) => {
    if (Array.isArray(node)) {
      node.forEach((child, i) => walk(child, `${at}[${i}]`));
      return;
    }
    if (!node || typeof node !== 'object') return;
    for (const [key, value] of Object.entries(node)) {
      if (key === '$ref' && typeof value === 'string' && value.startsWith('#/')) {
        const segments = value.slice(2).split('/');
        let cursor = spec;
        for (const segment of segments) {
          const unescaped = segment.replace(/~1/g, '/').replace(/~0/g, '~');
          if (!cursor || typeof cursor !== 'object' || !(unescaped in cursor)) {
            missing.push(`${at}: ${value}`);
            break;
          }
          cursor = cursor[unescaped];
        }
      } else {
        walk(value, `${at}/${key}`);
      }
    }
  };
  walk(spec, '');
  if (missing.length > 0) {
    throw new Error(`openapi.yaml has unresolvable $ref(s):\n  - ${missing.join('\n  - ')}`);
  }
}

export function buildManifest(yamlText) {
  // uniqueKeys makes a duplicated mapping key a parse error. Without it the parser silently keeps
  // the last definition, so a schema redefined by accident looks fine in review and the earlier
  // one quietly disappears - which is exactly how one of these was written by hand.
  const spec = parse(yamlText, { uniqueKeys: true });
  assertRefsResolve(spec);
  const schemas = spec.components?.schemas ?? {};

  const operations = [];
  for (const [path, pathItem] of Object.entries(spec.paths ?? {})) {
    for (const verb of METHOD_VERBS) {
      const op = pathItem[verb];
      if (!op) continue;
      const success = Object.entries(op.responses ?? {}).find(([code]) => code.startsWith('2'));
      const responseSchema = success?.[1]?.content?.['application/json']?.schema;
      const requestSchema = op.requestBody?.content?.['application/json']?.schema;
      operations.push({
        operationId: op.operationId ?? `${verb} ${path}`,
        method: verb.toUpperCase(),
        path,
        // Explicit `security: []` in the spec is the only way an endpoint becomes public;
        // anything else inherits the global requirement.
        public: Array.isArray(op.security) && op.security.length === 0,
        requestBody: localRef(requestSchema?.$ref) ? requestSchema.$ref.split('/').pop() : null,
        responseSchema: localRef(responseSchema?.$ref)
          ? responseSchema.$ref.split('/').pop()
          : responseSchema?.type === 'object'
            ? '(inline)'
            : null,
      });
    }
  }

  return {
    openapi: spec.openapi,
    specVersion: spec.info?.version ?? null,
    operations: operations.sort((a, b) => `${a.method} ${a.path}`.localeCompare(`${b.method} ${b.path}`)),
    schemas: Object.fromEntries(
      Object.keys(schemas)
        .sort()
        .map((name) => [name, describeSchema(name, schemas[name])]),
    ),
  };
}

export function serialize(manifest) {
  return `${JSON.stringify(manifest, null, 2)}\n`;
}

// npm invokes this as `node shared/scripts/gen-manifest.mjs`, i.e. argv[1] is *relative*.
// Comparing it to an absolute path silently never matches (on Windows it also differs in
// separators), so resolve both sides to file URLs first.
if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const manifest = buildManifest(readFileSync(specPath, 'utf8'));
  writeFileSync(outPath, serialize(manifest), 'utf8');
  process.stdout.write(
    `contract.json written: ${manifest.operations.length} operations, ${Object.keys(manifest.schemas).length} schemas\n`,
  );
}
