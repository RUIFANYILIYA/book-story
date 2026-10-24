import { describe, expect, it } from 'vitest';
import { detectImageType, isSafeCoverImage, placeholderCoverSvg } from '../src/library/covers.ts';

const PNG = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 1]);
const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0, 0, 0x10, 0x4a, 0, 0, 0, 0]);
const GIF = Uint8Array.from([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 2, 3, 4, 5, 6]);
const WEBP = Uint8Array.from([
  0x52, 0x49, 0x46, 0x46, 0x20, 0, 0, 0, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20,
]);

describe('detectImageType', () => {
  it('names the formats a cover can realistically be', () => {
    expect(detectImageType(PNG)?.mediaType).toBe('image/png');
    expect(detectImageType(JPEG)?.mediaType).toBe('image/jpeg');
    expect(detectImageType(GIF)?.mediaType).toBe('image/gif');
    expect(detectImageType(WEBP)?.mediaType).toBe('image/webp');
  });

  it('trusts the bytes over the extension', () => {
    // A file called cover.jpg that is actually a PNG is common enough that serving it as
    // image/jpeg breaks strict clients, so the filename is never consulted here.
    expect(detectImageType(PNG)?.extension).toBe('png');
  });

  it('refuses to guess at something it does not recognise', () => {
    expect(detectImageType(Uint8Array.from([1, 2, 3]))).toBeNull();
    expect(detectImageType(Uint8Array.from(Buffer.from('<?xml version="1.0"?><svg')))).toBeNull();
    expect(isSafeCoverImage(Uint8Array.from(Buffer.from('MZ\u0000\u0003')))).toBe(false);
    expect(isSafeCoverImage(PNG)).toBe(true);
  });
});

describe('placeholderCoverSvg', () => {
  const base = { title: '远山灯火落在无人知晓的港口', author: '林某', seed: 'book-1' };

  it('produces a well-formed SVG document', () => {
    const svg = placeholderCoverSvg(base).toString('utf8');
    expect(svg.startsWith('<svg xmlns="http://www.w3.org/2000/svg"')).toBe(true);
    expect(svg.trimEnd().endsWith('</svg>')).toBe(true);
    expect(svg).toContain('远山灯火落在无人');
    expect(svg).toContain('林某');
  });

  it('is byte-identical for the same seed, so tiles do not change colour between requests', () => {
    expect(placeholderCoverSvg(base).equals(placeholderCoverSvg(base))).toBe(true);
    expect(placeholderCoverSvg(base).equals(placeholderCoverSvg({ ...base, seed: 'book-2' }))).toBe(
      false,
    );
  });

  it('escapes markup that a title legitimately contains', () => {
    // Unescaped text here lets a filename become an attribute injection in a document the admin
    // page embeds as an <img>; the escape has to apply to what is rendered, so it is asserted on
    // the output rather than on a helper.
    const svg = placeholderCoverSvg({
      title: `A & B "<script>"`,
      author: `x' y`,
      seed: 's',
    }).toString('utf8');
    expect(svg).not.toContain('<script>');
    expect(svg).toContain('&amp;');
    expect(svg).toContain('&quot;');
    expect(svg).toContain('&apos;');
  });

  it('wraps a long CJK title into bounded lines instead of overflowing the tile', () => {
    const svg = placeholderCoverSvg({
      title: '这是一个非常长的中文书名以至于它必须被折成多行才能放进封面里否则就会溢出边界',
      seed: 's',
    }).toString('utf8');
    const textLines = (svg.match(/<text /gu) ?? []).length;
    expect(textLines).toBeGreaterThan(1);
    expect(textLines).toBeLessThanOrEqual(6);
  });

  it('wraps latin by word rather than by character', () => {
    const svg = placeholderCoverSvg({
      title: 'The Quiet Harbour and the Long Winter Night',
      seed: 's',
    }).toString('utf8');
    for (const line of svg.match(/>([^<>]+)<\/text>/gu) ?? []) {
      // No latin word is split across two lines, which character-wrapping would do.
      expect(line).not.toMatch(/[a-z]{3,}$/u);
    }
  });

  it('falls back to a visible placeholder for an untitled book', () => {
    expect(placeholderCoverSvg({ title: '   ', seed: 's' }).toString('utf8')).toContain('未命名');
  });
});
