/**
 * Cover handling without an image library.
 *
 * `sharp` would be the obvious tool, but it is a native module and the entire reason this image
 * builds on a NAS without a toolchain is that nothing in the runtime tree has to be compiled or
 * matched to a platform. So the server stores the cover bytes the container provided, hands them
 * out unmodified, and lets the client downsample at decode time — which Coil and every modern
 * browser already do. Resizing can be added later behind a capability flag without changing a
 * single client, because the URL already carries the requested width.
 *
 * What the server does generate is the placeholder for the formats that carry no art at all
 * (TXT, and PDF until a renderer exists). It is an SVG built from the title, with a palette
 * derived from the book's own hash so the same book always gets the same colour — a shelf that
 * reshuffles colours on every request looks broken even when every tile is individually fine.
 */

export interface DetectedImage {
  mediaType: string;
  /** Extension offered to clients in Content-Disposition. */
  extension: string;
}

/** Magic bytes, because a `.jpg` holding a PNG is commoner than you would hope. */
export function detectImageType(bytes: Uint8Array): DetectedImage | null {
  if (bytes.length < 12) return null;
  const hex = (at: number, n: number) =>
    [...bytes.slice(at, at + n)].map((b) => b.toString(16).padStart(2, '0')).join('');

  if (hex(0, 3) === 'ffd8ff') return { mediaType: 'image/jpeg', extension: 'jpg' };
  if (hex(0, 8) === '89504e470d0a1a0a') return { mediaType: 'image/png', extension: 'png' };
  const g = hex(0, 6);
  if (g === '474946383761' || g === '474946383961') return { mediaType: 'image/gif', extension: 'gif' };
  if (hex(0, 4) === '52494646' && hex(8, 4) === '57454250') return { mediaType: 'image/webp', extension: 'webp' };
  if (hex(0, 2) === '424d') return { mediaType: 'image/bmp', extension: 'bmp' };
  // An FPX/SVGZ or a truncated file reaching here is not worth guessing about.
  return null;
}

/** Refuse anything we cannot name, rather than storing an arbitrary blob as a "cover". */
export function isSafeCoverImage(bytes: Uint8Array): boolean {
  return detectImageType(bytes) !== null;
}

const PALETTES: readonly [string, string, string][] = [
  // background, foreground, accent
  ['#1f2933', '#f5f7fa', '#7fb2d9'],
  ['#2d2a26', '#f3ede4', '#c9a227'],
  ['#14324a', '#eaf2f8', '#8fd3c7'],
  ['#33212e', '#f7ecf1', '#d98ba8'],
  ['#20301f', '#eff5ea', '#9dc48a'],
  ['#2b2b3d', '#f0f0fa', '#a99ce0'],
];

/** Escape for XML text and attribute content; titles contain `&`, `<` and quotes routinely. */
function xmlEscape(value: string): string {
  return value
    .replace(/&/gu, '&amp;')
    .replace(/</gu, '&lt;')
    .replace(/>/gu, '&gt;')
    .replace(/"/gu, '&quot;')
    .replace(/'/gu, '&apos;');
}

function hashOf(seed: string): number {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i += 1) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

/**
 * Greedy line wrapping for the placeholder.
 *
 * CJK text has no spaces, so splitting on whitespace yields one enormous "word" and a title like
 * 远山灯火落在无人知晓的港口 either overflows the tile or shrinks to nothing. Breaking by
 * character for CJK-dominant titles and by word otherwise is what keeps both readable.
 */
function wrapTitle(title: string, maxCharsPerLine: number, maxLines: number): string[] {
  const lines: string[] = [];
  if (isCjkTitle(title)) {
    for (let i = 0; i < title.length && lines.length < maxLines; i += maxCharsPerLine) {
      lines.push(title.slice(i, i + maxCharsPerLine));
    }
    return lines;
  }
  let current = '';
  for (const word of title.split(/\s+/u)) {
    if (current.length === 0) current = word;
    else if (current.length + 1 + word.length <= maxCharsPerLine) current += ` ${word}`;
    else {
      lines.push(current);
      current = word;
      if (lines.length === maxLines) break;
    }
  }
  if (current.length > 0 && lines.length < maxLines) lines.push(current);
  return lines;
}

function isCjkTitle(title: string): boolean {
  const cjk = (title.match(/[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/gu) ?? []).length;
  return cjk > title.length * 0.3;
}

export interface PlaceholderOptions {
  title: string;
  author?: string | null;
  /** Usually the book or blob id; only used to pick a stable colour. */
  seed: string;
  width?: number;
  height?: number;
}

export function placeholderCoverSvg(options: PlaceholderOptions): Buffer {
  const width = options.width ?? 480;
  const height = options.height ?? 720;
  const [background, foreground, accent] = PALETTES[hashOf(options.seed) % PALETTES.length]!;
  const title = options.title.trim() || '未命名';
  const lines = wrapTitle(title, isCjkTitle(title) ? 9 : 16, 5);
  const fontSize = Math.round(Math.min(46, Math.max(26, 300 / Math.max(1, lines.length))));
  const lineHeight = Math.round(fontSize * 1.35);
  const blockTop = Math.round(height * 0.3);

  const spans = lines
    .map(
      (line, index) =>
        `    <text x="${width / 2}" y="${blockTop + index * lineHeight}" font-size="${fontSize}">${xmlEscape(line)}</text>`,
    )
    .join('\n');

  const author =
    options.author && options.author.trim().length > 0
      ? `  <text x="${width / 2}" y="${height - 74}" font-size="26" fill="${accent}" opacity="0.95">${xmlEscape(options.author.trim())}</text>\n`
      : '';

  return Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img">
  <rect width="${width}" height="${height}" fill="${background}"/>
  <rect x="28" y="28" width="${width - 56}" height="${height - 56}" fill="none" stroke="${accent}" stroke-opacity="0.35" stroke-width="2"/>
  <g fill="${foreground}" font-family="'PingFang SC','Microsoft YaHei','Noto Sans CJK SC',system-ui,sans-serif" font-weight="600" text-anchor="middle">
${spans}
  </g>
${author}</svg>
`,
    'utf8',
  );
}

export const SVG_MEDIA_TYPE = 'image/svg+xml';
