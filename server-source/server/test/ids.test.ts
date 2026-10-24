import { describe, expect, it } from 'vitest';
import { isId, newId, timestampOfId } from '../src/ids.ts';

describe('newId', () => {
  it('is 26 chars of the Crockford alphabet', () => {
    const id = newId();
    expect(id).toHaveLength(26);
    // I, L, O and U are excluded so a hand-transcribed id cannot be misread.
    expect(id).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
  });

  it('sorts by creation time', () => {
    const earlier = newId(Date.parse('2026-01-01T00:00:00Z'));
    const later = newId(Date.parse('2026-06-01T00:00:00Z'));
    expect(earlier.localeCompare(later)).toBeLessThan(0);
  });

  it('orders within the same millisecond without colliding', () => {
    const at = Date.parse('2026-03-01T00:00:00Z');
    const ids = new Set(Array.from({ length: 500 }, () => newId(at)));
    expect(ids.size).toBe(500);
  });

  it('round-trips its timestamp', () => {
    const at = Date.parse('2026-09-19T08:30:00.000Z');
    expect(timestampOfId(newId(at))).toBe(at);
  });

  it('rejects timestamps that would not fit rather than silently wrapping', () => {
    expect(() => newId(1.5)).toThrow(/out of range/);
    expect(() => newId(-1)).toThrow(/out of range/);
    expect(() => newId(2 ** 48)).toThrow(/out of range/);
  });
});

describe('isId', () => {
  it('accepts a generated id and rejects look-alikes', () => {
    expect(isId(newId())).toBe(true);
    expect(isId('not-an-id')).toBe(false);
    expect(isId(newId().slice(0, 25))).toBe(false);
    expect(isId(newId().replace(/^[0-9A-HJKMNP-TV-Z]/, 'I'))).toBe(false);
    expect(isId(null)).toBe(false);
    expect(isId(42)).toBe(false);
  });
});
