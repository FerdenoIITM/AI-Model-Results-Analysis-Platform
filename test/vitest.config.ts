import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["api/**/*.test.ts"],
    // Every file resets the shared database in beforeAll, so files must not overlap.
    fileParallelism: false,
    testTimeout: 30000,
    hookTimeout: 120000,
  },
});
