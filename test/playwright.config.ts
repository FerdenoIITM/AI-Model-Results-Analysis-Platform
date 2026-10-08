import { defineConfig, devices } from "@playwright/test";

const reportFile = process.env.PLAYWRIGHT_JSON_OUTPUT ?? "/reports/e2e.json";

export default defineConfig({
  testDir: "e2e",
  // Specs reset the shared database in beforeAll, so they run one at a time.
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  outputDir: "/tmp/amrap-e2e-results",
  reporter: [["list"], ["json", { outputFile: reportFile }]],
  use: {
    baseURL: process.env.WEB_URL ?? "http://gateway",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } } }],
});
