import { test, expect } from "@playwright/test";
test("local app authenticates against Spring and displays persisted data", async ({
  page,
}) => {
  test.skip(
    process.env.RUN_LIVE_UI !== "true",
    "Requires the native local stack or Docker API",
  );
  await page.goto(process.env.LIVE_BASE_URL || "http://127.0.0.1:5173/");
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Chào Minh Anh, một ngày sáng tạo mới" }),
  ).toBeVisible();
  await expect(
    page.getByText("Dữ liệu thử nghiệm · Đăng thật đã tắt"),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/live-dashboard.png",
    fullPage: true,
    animations: "disabled",
  });
  await page
    .getByRole("button", { name: "Content Studio", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Content Studio" }),
  ).toBeVisible();
  const img = page.locator(".content-image img").first();
  await expect(img).toBeVisible();
  await expect
    .poll(() => img.evaluate((e: HTMLImageElement) => e.naturalWidth))
    .toBe(1080);
  await page.screenshot({
    path: "test-results/live-content-studio.png",
    fullPage: true,
    animations: "disabled",
  });
  await page.getByRole("button", { name: "Khách hàng", exact: true }).click();
  await expect(page.getByText("smoke@example.com").first()).toBeVisible();
  await page
    .getByRole("button", { name: "Lịch nội dung", exact: true })
    .click();
  await expect(page.locator(".publication-row").first()).toContainText(
    "Đã xuất bản",
  );
});

test("local ChatGPT settings starts official PKCE OAuth without provider calls", async ({
  page,
}) => {
  test.skip(process.env.RUN_LIVE_UI !== "true", "Requires Docker app");
  await page.goto(process.env.LIVE_BASE_URL || "http://127.0.0.1:5173/");
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(page.getByRole("navigation")).toBeVisible();
  const authOrigin = new URL(page.url()).origin;
  const statusResponse = await page.request.get(authOrigin + "/v1/ai/chatgpt");
  expect(statusResponse.ok()).toBe(true);
  const existingAi = await statusResponse.json();
  if (existingAi.active_id) {
    await page.route("**/v1/ai/chatgpt/*/models", (r) =>
      r.fulfill({ json: [] }),
    );
    await page.addInitScript(() => localStorage.clear());
  }
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Kết nối AI", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Dùng gói ChatGPT để tạo nội dung" }),
  ).toBeVisible();
  const welcome = page.getByRole("button", { name: "Đã hiểu · Got it" });
  if (existingAi.active_id) await welcome.click();
  await expect(
    page.getByRole("button", { name: "Continue with ChatGPT" }),
  ).toBeEnabled();
  await page.screenshot({
    path: "test-results/live-chatgpt-settings.png",
    fullPage: true,
    animations: "disabled",
  });
  // Stop before provider sign-in: no human credentials, provider registration or paid inference.
  await page.route("https://auth.openai.com/**", (r) =>
    r.fulfill({ contentType: "text/html", body: "<p>OAuth test boundary</p>" }),
  );
  await page.getByRole("button", { name: "Continue with ChatGPT" }).click();
  await expect(page).toHaveURL(
    /https:\/\/auth\.openai\.com\/api\/accounts\/authorize\?/,
  );
  const parameters = new URL(page.url()).searchParams;
  expect(parameters.get("client_id")).toBe("dynamic_agent_client");
  expect(parameters.get("code_challenge_method")).toBe("S256");
  expect(parameters.get("redirect_uri")).toBe(
    "http://127.0.0.1:5173/v1/ai/chatgpt/callback",
  );
  expect(parameters.get("scope")).toContain("chatgpt.tokens.use.direct");
  expect(parameters.get("code_challenge")).toHaveLength(43);
  expect(parameters.get("ext_agent_host_id")).toMatch(/^urn:uuid:/);
});

test("local daily setup creates a private preview without any Facebook publishing", async ({
  page,
}) => {
  test.skip(process.env.RUN_LIVE_UI !== "true", "Requires Docker app");
  test.setTimeout(60000);
  await page.goto(process.env.LIVE_BASE_URL || "http://127.0.0.1:5173/");
  if (process.env.AUTOPOST_TEST_EMAIL) {
    await page.getByLabel("Email").fill(process.env.AUTOPOST_TEST_EMAIL);
    await page.getByLabel("Mật khẩu").fill("demo-marketing");
  }
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(page.getByRole("navigation")).toBeVisible();
  const liveOrigin = new URL(page.url()).origin;
  const aiResponse = await page.request.get(liveOrigin + "/v1/ai/chatgpt");
  expect(aiResponse.ok()).toBe(true);
  const ai = await aiResponse.json();
  test.skip(
    !!ai.active_id || ai.api_configured,
    "Live preview test must never use a real AI account or paid API key",
  );
  await page.getByRole("button", { name: "Đăng tự động", exact: true }).click();
  await expect(
    page.getByRole("heading", {
      name: "Thiết lập một lần. Có bài mới mỗi ngày.",
    }),
  ).toBeVisible();
  await page
    .getByLabel("Mục tiêu nội dung", { exact: true })
    .fill("Chia sẻ thông tin thương hiệu và giải đáp câu hỏi thường gặp.");
  await page
    .getByLabel("Chủ đề hằng ngày · mỗi dòng một chủ đề")
    .fill(
      "Giới thiệu thương hiệu\nCâu hỏi thường gặp\nChuẩn bị trước buổi trao đổi",
    );
  await page.getByRole("radio", { name: /Chạy thử toàn bộ/ }).check();
  await page.getByRole("radio", { name: /Ảnh mẫu thương hiệu/ }).check();
  await page.getByRole("button", { name: "Tạo bài thử", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "Bài viết & ảnh" }),
  ).toBeVisible({ timeout: 45000 });
  const image = page.getByRole("dialog").locator("img");
  await expect
    .poll(() => image.evaluate((e: HTMLImageElement) => e.naturalWidth))
    .toBe(1080);
  await expect(page.getByRole("dialog")).toContainText(
    "Bài thử · Không đăng Facebook",
  );
  await page.screenshot({
    path: "test-results/live-autopost-preview.png",
    fullPage: true,
    animations: "disabled",
  });
  await page.getByRole("button", { name: "Đóng", exact: true }).click();
  await expect(
    page.getByText("Chưa bật lịch tự động", { exact: true }),
  ).toBeVisible();
  const brands = await (
    await page.request.get(liveOrigin + "/v1/brands")
  ).json();
  const status = await (
    await page.request.get(liveOrigin + `/v1/brands/${brands[0].id}/autopost`)
  ).json();
  expect(status.config.enabled).toBe(false);
  expect(status.runs[0].autopost_preview).toBe(true);
  expect(status.runs[0].publication_status).toBeNull();
  await page.screenshot({
    path: "test-results/live-autopost-desktop.png",
    fullPage: true,
    animations: "disabled",
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    )
    .toBe(true);
  await page.screenshot({
    path: "test-results/live-autopost-mobile.png",
    fullPage: true,
    animations: "disabled",
  });
});
