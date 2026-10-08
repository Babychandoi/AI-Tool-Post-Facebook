import { test, expect } from "@playwright/test";
const brand = {
  id: "brand-a",
  name: "Poip Law · Demo",
  industry: "Dịch vụ pháp lý",
  voice: "Chuyên nghiệp, dễ hiểu",
  audience: "Chủ doanh nghiệp",
  color: "#8b76ef",
  timezone: "Asia/Ho_Chi_Minh",
  enabled: true,
  mode: "ASSISTED",
  version: 1,
  policy_version: 1,
  monthly_budget_micros: 10000000,
  public_form_key: "form-a",
};
const revision = {
  id: "revision-a",
  brand_id: "brand-a",
  content_id: "content-a",
  hook: "Chuẩn bị gì trước buổi trao đổi đầu tiên?",
  body: "Ghi lại nhu cầu, bối cảnh và các câu hỏi bạn muốn tìm hiểu.",
  cta: "Lưu lại để tham khảo.",
  source_refs: ["source-a"],
  risk_flags: [],
  status: "IN_REVIEW",
  snapshot_hash: "fixture-hash",
  revision_no: 1,
  created_at: new Date().toISOString(),
};
const pixel =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
test.beforeEach(async ({ page }) => {
  await page.route("**/v1/**", async (route) => {
    const url = new URL(route.request().url()),
      path = url.pathname;
    let response: any = [];
    if (path === "/v1/auth/me")
      response = {
        email: "owner@demo.local",
        name: "Minh Anh",
        role: "OWNER",
        workspace: "workspace-a",
        brands: null,
      };
    else if (path === "/v1/auth/csrf") response = { token: "fixture-csrf" };
    else if (path === "/v1/ai/chatgpt")
      response = {
        enabled: true,
        accounts: [],
        active_id: null,
        api_configured: false,
      };
    else if (path === "/v1/brands") response = [brand];
    else if (path === "/v1/dashboard")
      response = {
        brand,
        demo: true,
        live_publishing: false,
        counts: {
          pending_review: 1,
          scheduled: 0,
          published: 0,
          leads: 0,
          attention: 0,
        },
        budget: { spent_micros: 0, reserved_micros: 0 },
        activity: [
          { action: "REVISION_CREATED", created_at: new Date().toISOString() },
        ],
      };
    else if (path === "/v1/contents") response = [revision];
    else if (path.endsWith("/image")) {
      await route.fulfill({
        contentType: "image/png",
        body: Buffer.from(pixel, "base64"),
      });
      return;
    } else if (path.endsWith("/knowledge"))
      response = [
        {
          id: "source-a",
          title: "Quy trình tiếp nhận",
          body: "Chuẩn bị nhu cầu và câu hỏi trước buổi trao đổi.",
          status: "APPROVED",
          version: 1,
          expired: false,
        },
      ];
    else if (path.endsWith("/accounts"))
      response = [
        {
          id: "account-a",
          name: "Page thử nghiệm",
          provider: "DEMO",
          health: "CONNECTED",
        },
      ];
    else if (path === "/v1/reports")
      response = { metrics: [], lead_stages: [] };
    else if (path === "/v1/operations")
      response = { jobs: [], audit: [], attempts: [], events: [] };
    else if (path === "/v1/leads") response = { items: [], next_cursor: null };
    else if (path.endsWith("/revisions")) response = [revision];
    else if (route.request().method() === "POST") response = { ok: true };
    await route.fulfill({ json: response });
  });
});
test("dashboard renders backend counts and handles mobile without overflow", async ({
  page,
}) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Chào Minh Anh, một ngày sáng tạo mới" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: /Bài chờ duyệt/ }),
  ).toContainText("01");
  await page.screenshot({
    path: "test-results/dashboard-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    page.getByRole("heading", { name: "Từ ý tưởng đến kết nối." }),
  ).toBeVisible();
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    )
    .toBe(true);
  await page.screenshot({
    path: "test-results/dashboard-mobile.png",
    fullPage: true,
  });
});
test("approval carries exact immutable revision hash and CSRF token", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByRole("button", { name: /Hàng đợi duyệt/ }).click();
  await page.getByRole("button", { name: "Xem & duyệt" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  const request = page.waitForRequest((r) =>
    r.url().endsWith("/revisions/revision-a/approvals"),
  );
  await page.getByRole("button", { name: "Duyệt bài", exact: true }).click();
  const r = await request;
  expect(r.postDataJSON()).toEqual({
    decision: "APPROVED",
    note: "",
    expected_hash: "fixture-hash",
  });
  expect(r.headers()["x-xsrf-token"]).toBe("fixture-csrf");
  await expect(page.getByRole("status")).toContainText(
    "Đã duyệt revision này.",
  );
});
test("generation uses asynchronous run API and idempotency key", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Tạo nội dung", exact: true }).click();
  await page
    .getByLabel("Chủ đề bạn muốn chia sẻ")
    .fill("Một buổi trao đổi hiệu quả");
  const request = page.waitForRequest(
    (r) => r.url().endsWith("/v1/runs") && r.method() === "POST",
  );
  await page.getByRole("button", { name: "Bắt đầu tạo nội dung" }).click();
  const r = await request;
  expect(r.postDataJSON()).toMatchObject({
    brand_id: "brand-a",
    topic: "Một buổi trao đổi hiệu quả",
  });
  expect(r.headers()["idempotency-key"]).toBeTruthy();
  await expect(page.getByRole("status")).toContainText("Tác vụ đã xếp hàng");
});
test("missing metrics are visible rather than fabricated as zero", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Báo cáo", exact: true }).click();
  await expect(page.getByText("Chờ dữ liệu từ Page")).toBeVisible();
  await expect(
    page.getByText(/Dữ liệu demo không có số tương tác giả/),
  ).toBeVisible();
});

test("ChatGPT settings shows account catalog and saves model with CSRF", async ({
  page,
}) => {
  let saved: any;
  await page.route(/\/v1\/ai\/chatgpt(?:\/.*)?$/, async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/models"))
      return route.fulfill({
        json: [{ slug: "allowed-model", display_name: "Model tài khoản" }],
      });
    if (path.endsWith("/model")) {
      saved = {
        body: route.request().postDataJSON(),
        headers: route.request().headers(),
      };
      return route.fulfill({ json: { ok: true } });
    }
    return route.fulfill({
      json: {
        enabled: true,
        api_configured: false,
        active_id: "connection-fixture",
        accounts: [
          {
            id: "connection-fixture",
            account_email: "person@example.com",
            status: "CONNECTED",
            active: true,
            model: null,
          },
        ],
      },
    });
  });
  await page.goto("/");
  await page
    .getByRole("button", { name: "Kết nối AI", exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("heading", { name: "Dùng gói ChatGPT để tạo nội dung" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Đã hiểu · Got it" }).click();
  await expect(
    page.getByText("person@example.com", { exact: true }),
  ).toBeVisible();
  await page.getByLabel("Model ChatGPT").selectOption("allowed-model");
  await page.getByRole("button", { name: "Lưu model", exact: true }).click();
  await expect(
    page.getByText(
      "Đã chọn model. Bản nháp tiếp theo sẽ dùng hạn mức ChatGPT của bạn.",
    ),
  ).toBeVisible();
  expect(saved.body).toEqual({ model: "allowed-model" });
  expect(saved.headers["x-xsrf-token"]).toBe("fixture-csrf");
  await expect(
    page.getByRole("link", { name: /Quản lý hạn mức · Manage usage/ }),
  ).toHaveAttribute("href", "https://chatgpt.com/settings/usage");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
});

test("daily autopost saves one setup, previews without posting, and requires explicit enable", async ({
  page,
}) => {
  let config: any = null;
  const writes: any[] = [];
  let previews = 0;
  await page.route(
    /\/v1\/brands\/brand-a\/autopost(?:\/.*)?$/,
    async (route) => {
      const req = route.request();
      if (req.method() === "PUT") {
        const value = req.postDataJSON();
        writes.push(value);
        expect(req.headers()["x-xsrf-token"]).toBe("fixture-csrf");
        config = {
          ...value,
          id: "autopost-a",
          version: (config?.version || 0) + 1,
        };
      }
      if (req.url().endsWith("/preview")) {
        previews++;
        await route.fulfill({ status: 202, json: { run_id: "preview-a" } });
        return;
      }
      if (req.url().endsWith("/stop"))
        config = { ...config, enabled: false, version: config.version + 1 };
      await route.fulfill({
        json: {
          config,
          checks: [],
          ai_provider: "DEMO",
          live_available: false,
          meta_available: false,
          image_api_available: false,
          runs: [],
        },
      });
    },
  );
  await page.goto("/#autopost");
  await expect(
    page.getByRole("heading", { name: "Đăng tự động", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", {
      name: "Thiết lập một lần. Có bài mới mỗi ngày.",
    }),
  ).toBeVisible();
  await page
    .getByLabel("Mục tiêu nội dung", { exact: true })
    .fill("Chia sẻ thông tin hữu ích");
  await page
    .getByLabel("Chủ đề hằng ngày · mỗi dòng một chủ đề")
    .fill("Mẹo chuẩn bị\nCâu hỏi thường gặp");
  await page.getByRole("radio", { name: /Chạy thử toàn bộ/ }).check();
  await page.getByRole("button", { name: "Tạo bài thử", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("không đăng Facebook");
  expect(previews).toBe(1);
  expect(writes[0].enabled).toBe(false);
  expect(writes[0].mode).toBe("DEMO");
  expect(writes[0].pillars).toEqual(["Mẹo chuẩn bị", "Câu hỏi thường gặp"]);
  await page.getByRole("button", { name: "Bật lịch hằng ngày" }).click();
  await expect(page.getByText("Lịch đã bật", { exact: true })).toBeVisible();
  expect(writes[1].enabled).toBe(true);
  await page.getByRole("button", { name: "Tắt đăng tự động" }).click();
  await expect(
    page.getByText("Chưa bật lịch tự động", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/autopost-desktop.png",
    fullPage: true,
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
    path: "test-results/autopost-mobile.png",
    fullPage: true,
  });
});

test("daily setup distinguishes ChatGPT text from billed images and shows blocked real publishing", async ({
  page,
}) => {
  await page.route(
    /\/v1\/brands\/brand-a\/autopost(?:\/.*)?$/,
    async (route) => {
      if (route.request().method() === "PUT") {
        await route.fulfill({
          status: 409,
          json: {
            message: "Chọn Fanpage đã kết nối; tự đăng cần Fanpage thật.",
          },
        });
        return;
      }
      await route.fulfill({
        json: {
          config: null,
          checks: [],
          ai_provider: "DEMO",
          live_available: false,
          meta_available: false,
          image_api_available: false,
          runs: [],
        },
      });
    },
  );
  await page.goto("/#autopost");
  await page
    .getByLabel("Mục tiêu nội dung", { exact: true })
    .fill("Thông tin thương hiệu");
  await page
    .getByLabel("Chủ đề hằng ngày · mỗi dòng một chủ đề")
    .fill("Mẹo hữu ích");
  await page.getByRole("radio", { name: /AI tự sinh ảnh/ }).check();
  await expect(
    page.getByText(/API tạo ảnh dùng hạn mức và phí riêng/),
  ).toBeVisible();
  await expect(page.getByLabel("OpenAI API key cho ảnh")).toHaveAttribute(
    "type",
    "password",
  );
  await page.getByRole("radio", { name: /Tự đăng lên Facebook/ }).check();
  await expect(page.getByText(/Máy chủ hiện đang tắt đăng thật/)).toBeVisible();
  await expect(
    page.getByRole("checkbox", { name: /Tôi cho phép hệ thống đăng thật/ }),
  ).not.toBeChecked();
  await page.getByRole("button", { name: "Bật lịch hằng ngày" }).click();
  await expect(page.getByRole("alert")).toContainText(
    "tự đăng cần Fanpage thật",
  );
  await expect(
    page.getByText("Chưa bật lịch tự động", { exact: true }),
  ).toBeVisible();
});

test("ChatGPT Web image experiment exposes bridge status and allows choosing posting mode", async ({ page }) => {
  await page.route(/\/v1\/brands\/brand-a\/autopost$/, route => route.fulfill({ json: {
    config: null, checks: [], runs: [], ai_provider: "DEMO",
    chatgpt_web_images: { ready: false, message: "Đăng nhập ChatGPT trong cửa sổ cầu nối để tạo ảnh." },
  } }));
  await page.goto("/#autopost");
  await page.getByRole("radio", { name: /ChatGPT Web · Thử nghiệm/ }).check();
  await expect(page.getByText(/Đăng nhập ChatGPT trong cửa sổ cầu nối/)).toBeVisible();
  await expect(page.getByRole("radio", { name: /Tự đăng lên Facebook/ })).toBeEnabled();
  await expect(page.getByRole("radio", { name: /Chạy thử toàn bộ/ })).toBeEnabled();
  await expect(page.getByLabel("OpenAI API key cho ảnh")).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
