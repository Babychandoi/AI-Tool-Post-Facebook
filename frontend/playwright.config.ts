import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "tests",
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  use: {
    baseURL: "http://127.0.0.1:5174",
    channel: "chrome",
    headless: true,
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "npm run dev -- --port 5174",
    url: "http://127.0.0.1:5174",
    reuseExistingServer: true,
  },
});
