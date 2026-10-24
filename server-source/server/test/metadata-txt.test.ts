import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { detectCharset, txtStats } from '../src/metadata/txt.ts';

/**
 * The server half of the cross-language golden cases in `shared/fixtures/txt-chapters`.
 *
 * Both sides decode the same bytes and must agree on the charset. This matters more than it looks:
 * GB18030 and UTF-8 decode each other's Chinese into *plausible-looking* text, so a disagreement
 * between the server (which renders the preview and counts the characters) and the reader (which
 * renders the book) is invisible until a user reports mojibake — and by then the wrong guess has
 * usually been persisted.
 *
 * The chapter expectations in `expected/<id>.json` are asserted by the Kotlin side
 * (`TxtChapterSplitterFixtureTest`), where the splitter lives; the files carry both so the shared
 * samples drive one contract rather than two drifting copies of it.
 */

const root = fileURLToPath(new URL('../../shared/fixtures/txt-chapters', import.meta.url));
const casesDir = join(root, 'cases');
const expectedDir = join(root, 'expected');

const ids = readdirSync(casesDir)
  .filter((name) => name.endsWith('.txt'))
  .map((name) => name.replace(/\.txt$/, ''))
  .sort();

describe('shared TXT golden cases', () => {
  it('ships cases and expectations for every documented family', () => {
    expect(ids.length).toBeGreaterThanOrEqual(9);
  });

  it.each(ids)('decodes %s to the charset its expectation records', (id) => {
    const bytes = readFileSync(join(casesDir, `${id}.txt`));
    const expected = JSON.parse(readFileSync(join(expectedDir, `${id}.json`), 'utf8')) as { charset: string };

    const detected = detectCharset(bytes);
    expect(detected.charset).toBe(expected.charset);
    // A plausible charset name over replacement characters would pass a name-only check, so the
    // decoded text is inspected too.
    expect(detected.text.length).toBeGreaterThan(0);
    expect(detected.text).not.toContain('\uFFFD');

    const stats = txtStats(detected.text);
    expect(stats.charCount).toBeGreaterThan(0);
    expect(stats.preview.length).toBeGreaterThan(0);
  });
});
