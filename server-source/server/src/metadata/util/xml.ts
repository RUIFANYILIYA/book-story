import { XMLParser } from 'fast-xml-parser';

/**
 * One XML configuration for every untrusted document the server reads.
 *
 * OPF, FB2 and docProps files are attacker-controllable the moment the server accepts an upload,
 * so the settings that matter here are the ones that stop a document from costing more than the
 * bytes it is made of: entity expansion, and unbounded attribute/element counts. Entity *fetching*
 * is never enabled, so an OPF with a SYSTEM path pointing at a local file cannot read it.
 */
export const XML_OPTIONS = {
  ignoreAttributes: false,
  attributeNamePrefix: '@_',
  // Text is data here; a parser that guesses types turns "007" into 7 and loses the leading zero
  // forever, which matters for identifiers and for the ISO date we compare on.
  parseTagValue: false,
  parseAttributeValue: false,
  trimValues: true,
  cdataPropName: '#cdata',
  maxAttributesCount: 200,
  maxTagNameLength: 1000,
} as const;

export function parseXml(text: string): Record<string, unknown> {
  return new XMLParser(XML_OPTIONS).parse(text) as Record<string, unknown>;
}

/**
 * The same hardening, for *content* rather than metadata: EPUB spine documents.
 *
 * `XML_OPTIONS` trims text and drops order, which is right when the answer is a title string and
 * wrong when the answer is a paragraph — "Hello <em>world</em> !" would lose the spaces around the
 * emphasis. Ordered mode yields `[{ tag: children }]` plus `{ '#text': … }` leaves, which is what
 * a faithful walk needs.
 */
export const CONTENT_XML_OPTIONS = {
  ...XML_OPTIONS,
  preserveOrder: true,
  trimValues: false,
  ignoreDeclaration: true,
  ignorePiTags: true,
} as const;

export type ContentNode = Record<string, unknown>;

export function parseContentXml(text: string): ContentNode[] {
  return new XMLParser(CONTENT_XML_OPTIONS).parse(text) as ContentNode[];
}

/** Fast-xml-parser yields `{}` for `<a></a>` and a string for `<a>x</a>`; normalize both. */
export function asRecord(value: unknown): Record<string, unknown> {
  if (value === null || value === undefined) return {};
  if (typeof value === 'object' && !Array.isArray(value)) return value as Record<string, unknown>;
  return {};
}

export function asRecords(value: unknown): Record<string, unknown>[] {
  if (value === null || value === undefined) return [];
  if (Array.isArray(value)) return value as Record<string, unknown>[];
  if (typeof value === 'object') return [value as Record<string, unknown>];
  return [];
}

/** Element text, whether the parser produced a string, a number, or a `#text` child. */
export function textOf(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value.trim() || null;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  const record = asRecord(value);
  const direct = record['#text'] ?? record['#cdata'];
  if (typeof direct === 'string') return direct.trim() || null;
  return null;
}

/**
 * Text of one or many sibling elements.
 *
 * This exists because fast-xml-parser yields a *bare string* for `<dc:title>x</dc:title>` but an
 * array when there are two. Running that through `asRecords` silently yields nothing, which is how
 * a single-title EPUB ends up with no title at all — the overwhelmingly common case.
 */
export function textList(value: unknown): string[] {
  if (value === null || value === undefined) return [];
  const items = Array.isArray(value) ? value : [value];
  const out: string[] = [];
  for (const item of items) {
    const text = textOf(item);
    if (text) out.push(text);
  }
  return out;
}

export function firstText(value: unknown): string | null {
  return textList(value)[0] ?? null;
}

/**
 * Attribute lookup that tolerates the namespace prefix.
 *
 * EPUB metadata writes the same property two ways depending on the tool that produced it:
 * `opf:role="aut"` inside a document that declares the `opf` prefix, or bare `role="aut"` where
 * the OPF namespace is the default one. Checking only one of them quietly drops every translator
 * credit from half of real-world libraries.
 */
export function attrString(node: Record<string, unknown>, name: string): string | null {
  for (const key of [`@_${name}`, `@_opf:${name}`, `@_dcterms:${name}`]) {
    const value = node[key];
    if (typeof value === 'string' && value.length > 0) return value;
  }
  return null;
}
