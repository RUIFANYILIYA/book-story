import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'node',
    // Each test file gets its own in-memory database, but the file-DB smoke test writes under
    // a temp dir; keeping files isolated avoids cross-file contention on the same SQLite file.
    isolate: true,
    pool: 'threads',
    coverage: {
      provider: 'v8',
      include: ['src/**/*.ts'],
      reporter: ['text', 'lcov'],
    },
    testTimeout: 20_000,
  },
});
