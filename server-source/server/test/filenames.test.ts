import { describe, expect, it } from 'vitest';
import {
  isCjkHeavy,
  normalizeTitle,
  parseFilename,
  sortTitle,
  stripExtension,
} from '../src/library/filenames.ts';

describe('stripExtension', () => {
  it('removes only a known ebook extension', () => {
    expect(stripExtension('a.epub')).toBe('a');
    expect(stripExtension('a.EPUB')).toBe('a');
    // `.bak` is not a known ebook extension, so the real extension is unknown and the name is
    // left whole rather than guessing that the inner `.txt` was the meaningful part.
    expect(stripExtension('notes.txt.bak')).toBe('notes.txt.bak');
    expect(stripExtension('no_extension')).toBe('no_extension');
    // A dot inside a name is not an extension.
    expect(stripExtension('v1.2 手册.pdf')).toBe('v1.2 手册');
    expect(stripExtension('path/to/书.epub')).toBe('书');
  });
});

describe('parseFilename', () => {
  it('reads the bracketed-title Chinese shape', () => {
    const hint = parseFilename('林某《远山灯火》.epub');
    expect(hint.title).toBe('远山灯火');
    expect(hint.author).toBe('林某');
  });

  it('reads an explicit author prefix', () => {
    const hint = parseFilename('作者：周甲《北方来信》.txt');
    expect(hint.title).toBe('北方来信');
    expect(hint.author).toBe('周甲');
  });

  it('splits a short left side as an author but refuses a long one', () => {
    expect(parseFilename('周甲 - 长夜将至.epub')).toEqual(
      expect.objectContaining({ author: '周甲', title: '长夜将至' }),
    );
    const ambiguous = parseFilename('Some Very Long Store Front Name - Season Two - Remastered.epub');
    // Splitting the wrong way here renames the book; leaving it whole is the safer failure.
    expect(ambiguous.author).toBeNull();
    expect(ambiguous.title).toContain('Season Two');
  });

  it('drops forum and download-manager noise without eating the title', () => {
    expect(parseFilename('海雾之城 - 精校版.epub').title).toBe('海雾之城');
    expect(parseFilename('海雾之城-副本.pdf').title).toBe('海雾之城');
    expect(parseFilename('旧城故事_vol.3.epub').title).toBe('旧城故事');
  });

  it('does not invent a series shelf out of a publication year', () => {
    const hint = parseFilename('冬谷记事 (2019).epub');
    expect(hint.year).toBe(2019);
    expect(hint.series).toBeNull();
    expect(hint.title).toBe('冬谷记事');
  });

  it('keeps a bracketed non-numeric qualifier as a series', () => {
    const hint = parseFilename('星海拾遗 [卷二] #4.epub');
    expect(hint.series).toBe('二');
    expect(hint.seriesIndex).toBe(4);
  });

  it('treats a leftover counter as no title at all', () => {
    expect(parseFilename('(1).epub').title).toBeNull();
    expect(parseFilename('x.pdf').title).toBeNull();
  });

  it('never trusts an extension it has seen before over the name', () => {
    // Sanitisation of the download filename happens at the boundary; this only has to not crash
    // or return a path.
    expect(parseFilename('../../../etc/passwd.epub').title).toBeTruthy();
    expect(parseFilename('../../../etc/passwd.epub').title).not.toContain('/');
  });
});

describe('normalizeTitle', () => {
  it('collapses width, case and punctuation so the dedupe index fires', () => {
    const a = normalizeTitle('  遠山 燈火!!  ');
    const b = normalizeTitle('远山灯火');
    expect(a).not.toBe(b); // different glyphs, deliberately
    expect(normalizeTitle('Star  Docker!!')).toBe('star docker');
    expect(normalizeTitle('STAR  DOCKER')).toBe('star docker');
    expect(normalizeTitle('ＳＴＡＲ ＤＯＣＫＥＲ')).toBe('star docker');
  });

  it('makes the duplicate guard agree with itself', () => {
    const titles = ['三体：死神永生', '  三体 死神永生 ', '三体——死神永生'];
    const norms = new Set(titles.map((t) => normalizeTitle(t)));
    expect(norms.size).toBe(1);
  });

  it('folds the leading article for sorting but not for display', () => {
    expect(sortTitle('The Long Dark')).toBe('long dark');
    expect(sortTitle('A Season of Rain')).toBe('season of rain');
    // "The" as the whole title must not sort to nothing.
    expect(sortTitle('The')).toBe('the');
  });
});

describe('isCjkHeavy', () => {
  it('separates scripts the word counter has to treat differently', () => {
    expect(isCjkHeavy('夜色如墨，远处传来汽笛声')).toBe(true);
    expect(isCjkHeavy('the night was dark and the sea was loud')).toBe(false);
  });
});
