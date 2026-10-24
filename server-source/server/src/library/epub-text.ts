import { openZipReader, TEXT_ENTRY_CAP, ZipError } from '../metadata/zip.ts';
import { findOpf, resolveHref } from '../metadata/epub.ts';
import {
  asRecord,
  asRecords,
  attrString,
  firstText,
  parseContentXml,
  parseXml,
  type ContentNode,
} from '../metadata/util/xml.ts';

/**
 * Cutting an EPUB into chapters for the console's reader.
 *
 * The reader shows one chapter at a time — that is what keeps a 500-chapter book from freezing the
 * tab — so the server does the part the browser cannot do cheaply: walk the spine, take each XHTML
 * document apart, and hand back plain blocks.
 *
 * Two decisions shape the output. Nothing from the file's own markup is passed through: the walk
 * rebuilds each paragraph from a whitelist of tags, so a chapter cannot smuggle a `<script>` (or an
 * `onclick`, or a remote `<img>`) into a page that holds an admin session. And images are dropped
 * rather than linked: they live inside the archive behind an authenticated endpoint, and a reader
 * that silently renders half of them would be worse than one that renders the text.
 */

export interface EpubChapter {
  /** TOC label: the document's first heading, its `<title>`, or "第 N 节". */
  title: string;
  /** Whitelisted blocks only: h1–h6, p, blockquote, ul/ol/li, pre, em/strong/code, br, hr. */
  html: string;
  /** Text length, which is what the reader's progress maths works in. */
  chars: number;
}

export interface EpubContent {
  chapters: EpubChapter[];
  totalChars: number;
  /** True when the archive was larger than the caps below and its tail was dropped. */
  truncated: boolean;
}

/** A book longer than this is not a book. The caps also bound the JSON a single request returns. */
const MAX_CHAPTERS = 2000;
const MAX_TOTAL_CHARS = 8_000_000;
/** One spine document larger than this is a rendering problem, not a chapter. */
const MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;

/** Elements that start a new block. Everything else is inline or dropped. */
const BLOCK_TAGS = new Set([
  'p', 'div', 'section', 'article', 'header', 'footer', 'aside', 'main', 'nav', 'figure',
  'figcaption', 'address', 'dl', 'dt', 'dd', 'table', 'thead', 'tbody', 'tr', 'td', 'th', 'li',
]);

const HEADING_TAGS = new Set(['h1', 'h2', 'h3', 'h4', 'h5', 'h6']);

/** Inline elements worth keeping, mapped to the smaller set the reader styles. */
const INLINE_TAGS: Record<string, string> = {
  em: 'em', i: 'em', strong: 'strong', b: 'strong', code: 'code',
  sub: 'sub', sup: 'sup', small: 'small', u: 'u', s: 's', strike: 's',
};

/**
 * Dropped with their contents. `img` is here on purpose: the bytes are inside the archive behind
 * an authenticated endpoint, and a chapter that half-renders its illustrations is worse than one
 * that shows the text. `script`/`style`/`iframe`/`object` are here because this output ends up in
 * the admin page's DOM.
 */
const DROP_TAGS = new Set([
  'script', 'style', 'head', 'title', 'meta', 'link', 'img', 'image', 'svg', 'math',
  'iframe', 'object', 'embed', 'audio', 'video', 'canvas', 'noscript', 'template',
]);

export async function extractEpubChapters(path: string): Promise<EpubContent> {
  const reader = await openZipReader(path);
  try {
    const warnings: string[] = [];
    const { opfPath, opfDir } = await findOpf(reader, warnings);
    const opf = asRecord(parseXml(await reader.readText(opfPath)));
    const pkg = asRecord(opf.package);
    const manifest = asRecord(pkg.manifest);
    const spine = asRecord(pkg.spine);

    const items = new Map<string, { href: string; mediaType: string }>();
    for (const item of asRecords(manifest.item)) {
      const id = attrString(item, 'id');
      const href = attrString(item, 'href');
      if (!id || !href) continue;
      items.set(id, { href: resolveHref(opfDir, href), mediaType: attrString(item, 'media-type') ?? '' });
    }

    // The book's own title, so a spine document whose `<title>` merely repeats it is not mistaken
    // for a chapter heading — that would give a 500-chapter TOC 500 identical entries.
    const metadata = asRecord(pkg.metadata);
    const bookTitle = firstText(metadata['dc:title']);

    // `linear="no"` marks auxiliary documents (notes, ads) that a reader skips by default.
    const spineItems = asRecords(spine.itemref)
      .filter((ref) => attrString(ref, 'linear') !== 'no')
      .map((ref) => items.get(attrString(ref, 'idref') ?? ''))
      .filter((item): item is { href: string; mediaType: string } => Boolean(item))
      .filter((item) => isReadableDocument(item.href, item.mediaType));

    const chapters: EpubChapter[] = [];
    let totalChars = 0;
    let truncated = false;

    for (const item of spineItems) {
      if (chapters.length >= MAX_CHAPTERS || totalChars >= MAX_TOTAL_CHARS) {
        truncated = true;
        break;
      }

      let document: string;
      try {
        document = await reader.readText(item.href, Math.min(MAX_DOCUMENT_BYTES, TEXT_ENTRY_CAP));
      } catch {
        // One unreadable entry must not sink the book: an EPUB with a broken chapter is still a
        // book, and the reader can open everything else.
        continue;
      }

      const rendered = renderDocument(document, bookTitle);
      if (!rendered) continue;

      chapters.push({
        title: rendered.title ?? `第 ${chapters.length + 1} 节`,
        html: rendered.html,
        chars: rendered.chars,
      });
      totalChars += rendered.chars;

      // Parsing is synchronous work on the event loop; a 600-chapter book would otherwise stall
      // every download and health check for seconds.
      if (chapters.length % 20 === 0) await new Promise((resolve) => setImmediate(resolve));
    }

    if (chapters.length === 0) {
      throw new ZipError('no readable chapters in the spine', 'missing');
    }
    return { chapters, totalChars, truncated };
  } finally {
    reader.close();
  }
}

function isReadableDocument(href: string, mediaType: string): boolean {
  const lower = mediaType.toLowerCase();
  if (lower.includes('xhtml') || lower.includes('html')) return true;
  return /\.x?html?$/iu.test(href);
}

interface Rendered {
  title: string | null;
  html: string;
  chars: number;
}

function renderDocument(xhtml: string, bookTitle: string | null): Rendered | null {
  const root = parseContentXml(xhtml);
  const body = findBody(root) ?? root;
  const blocks: string[] = [];
  const acc = { text: '', heading: null as string | null };

  walkBlocks(body, blocks, acc);

  // The TOC already shows the chapter title above the text, so a document that opens with its own
  // heading would print it twice.
  if (blocks.length > 0 && /^<h[1-6]>/u.test(blocks[0] ?? '') && acc.heading) blocks.shift();

  if (blocks.length === 0 && acc.text.trim().length === 0) return null;

  // `<title>` is a fallback for documents with no heading — but only when it says something the
  // book title does not, because many producers stamp the book title into every document.
  const documentTitle = findTitleText(root);
  const usableTitle =
    documentTitle && (!bookTitle || collapse(documentTitle).trim() !== collapse(bookTitle).trim())
      ? documentTitle
      : null;

  return { title: acc.heading ?? usableTitle, html: blocks.join(''), chars: acc.text.length };
}

/** The `<title>` element of the document's head, which is not content and is skipped by the walk. */
function findTitleText(nodes: ContentNode[]): string | null {
  for (const node of nodes) {
    const parts = splitNode(node);
    if (!parts) continue;
    if (parts.tag === 'title') {
      const text = parts.children
        .map((child) => (typeof child['#text'] === 'string' ? child['#text'] : ''))
        .join('')
        .trim();
      if (text.length > 0) return collapse(text).trim();
    }
    const nested = findTitleText(parts.children);
    if (nested) return nested;
  }
  return null;
}

function findBody(nodes: ContentNode[]): ContentNode[] | null {
  for (const node of nodes) {
    const parts = splitNode(node);
    if (!parts) continue;
    if (parts.tag === 'body') return parts.children;
    const nested = findBody(parts.children);
    if (nested) return nested;
  }
  return null;
}

function splitNode(node: ContentNode): { tag: string; children: ContentNode[] } | null {
  const key = Object.keys(node).find((k) => k !== ':@' && k !== '#text' && k !== '#cdata');
  if (!key) return null;
  const value = node[key];
  return {
    // Strip a namespace prefix (`xhtml:p`), which some producers write and none of the sets know.
    tag: key.toLowerCase().replace(/^[a-z0-9]+:/u, ''),
    children: Array.isArray(value) ? (value as ContentNode[]) : [],
  };
}

/** Block walk: everything it emits is either a whitelisted block or inline HTML inside one. */
function walkBlocks(nodes: ContentNode[], blocks: string[], acc: { text: string; heading: string | null }): void {
  let buffer = '';
  const flush = () => {
    const content = buffer.trim();
    buffer = '';
    if (content.length > 0) blocks.push(`<p>${content}</p>`);
  };

  for (const node of nodes) {
    if (!node) continue;
    const raw = node['#text'] ?? node['#cdata'];
    if (typeof raw === 'string') {
      const normalized = collapse(raw);
      if (normalized.length > 0) {
        buffer += escapeHtml(normalized);
        acc.text += normalized;
      }
      continue;
    }

    const parts = splitNode(node);
    if (!parts) continue;
    const { tag, children } = parts;
    if (DROP_TAGS.has(tag)) continue;

    if (tag === 'br') {
      buffer += '<br>';
      continue;
    }
    if (tag === 'hr') {
      flush();
      blocks.push('<hr>');
      continue;
    }
    if (HEADING_TAGS.has(tag)) {
      flush();
      const inner = renderInline(children, acc);
      if (!acc.heading) {
        const plain = plainText(inner);
        if (plain.length > 0) acc.heading = plain;
      }
      blocks.push(`<${tag}>${inner}</${tag}>`);
      continue;
    }
    if (tag === 'blockquote') {
      flush();
      const inner: string[] = [];
      walkBlocks(children, inner, acc);
      if (inner.length > 0) blocks.push(`<blockquote>${inner.join('')}</blockquote>`);
      continue;
    }
    if (tag === 'pre') {
      flush();
      const inner = renderInline(children, acc);
      if (inner.trim().length > 0) blocks.push(`<pre>${inner}</pre>`);
      continue;
    }
    if (tag === 'ul' || tag === 'ol') {
      flush();
      const items = children
        .filter((child) => splitNode(child)?.tag === 'li')
        .map((li) => `<li>${renderInline(splitNode(li)?.children ?? [], acc)}</li>`)
        .join('');
      if (items.length > 0) blocks.push(`<${tag}>${items}</${tag}>`);
      continue;
    }
    if (BLOCK_TAGS.has(tag)) {
      flush();
      walkBlocks(children, blocks, acc);
      continue;
    }

    // An inline element sitting at block level: keep it in the paragraph being built.
    const mapped = INLINE_TAGS[tag];
    const inner = renderInline(children, acc);
    buffer += mapped ? `<${mapped}>${inner}</${mapped}>` : inner;
  }

  flush();
}

/** Inline walk: returns HTML for the paragraph being built, never a block. */
function renderInline(nodes: ContentNode[], acc: { text: string; heading: string | null }): string {
  let out = '';
  for (const node of nodes) {
    if (!node) continue;
    const raw = node['#text'] ?? node['#cdata'];
    if (typeof raw === 'string') {
      const normalized = collapse(raw);
      if (normalized.length > 0) {
        out += escapeHtml(normalized);
        acc.text += normalized;
      }
      continue;
    }

    const parts = splitNode(node);
    if (!parts) continue;
    const { tag, children } = parts;
    if (DROP_TAGS.has(tag)) continue;
    if (tag === 'br') {
      out += '<br>';
      continue;
    }

    const inner = renderInline(children, acc);
    const mapped = INLINE_TAGS[tag];
    // Links lose their target and keep their words: an `href` that leaves the library is not
    // something this reader should follow, and the text is what the reader came for.
    out += mapped ? `<${mapped}>${inner}</${mapped}>` : inner;
  }
  return out;
}

/** Runs of whitespace collapse to one space, which is how HTML would have rendered them anyway. */
function collapse(text: string): string {
  return text.replace(/[ \t\r\n\u00a0]+/gu, ' ');
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/gu, '&amp;')
    .replace(/</gu, '&lt;')
    .replace(/>/gu, '&gt;')
    .replace(/"/gu, '&quot;')
    .replace(/'/gu, '&#39;');
}

function plainText(html: string): string {
  return html
    .replace(/<[^>]*>/gu, '')
    .replace(/&lt;/gu, '<')
    .replace(/&gt;/gu, '>')
    .replace(/&quot;/gu, '"')
    .replace(/&#39;/gu, "'")
    .replace(/&amp;/gu, '&')
    .replace(/\s+/gu, ' ')
    .trim();
}
