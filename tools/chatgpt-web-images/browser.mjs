import { chromium } from 'playwright';
import { readFile, stat, unlink } from 'node:fs/promises';

export class BridgeError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

// Only an assistant response created by this request may supply the output image.
export const assistantSelector = '[data-message-author-role="assistant"]';
const composerSelector = '#prompt-textarea, [contenteditable="true"][role="textbox"], textarea[placeholder]';

export async function createBrowser(profile, executablePath) {
  const context = await chromium.launchPersistentContext(profile, {
    headless: false,
    ...(executablePath ? { executablePath } : {}),
    acceptDownloads: true,
    viewport: { width: 1280, height: 900 },
  });
  const login = context.pages()[0] || await context.newPage();
  await login.goto('https://chatgpt.com/', { waitUntil: 'domcontentloaded' });
  return new ImageBrowser(context);
}

export class ImageBrowser {
  constructor(context) { this.context = context; this.busy = false; this.closed = false; }
  async status(checkSession = false) {
    if (this.closed) return { ready: false, code: 'BROWSER_CLOSED' };
    if (this.busy && !checkSession) return { ready: true, busy: true };
    for (const page of this.context.pages()) {
      if (page.url().startsWith('https://chatgpt.com/') && await page.locator(composerSelector).first().isVisible().catch(() => false)) {
        // A guest composer can be visible too; a visible Log in action means authentication is required.
        const login = page.getByRole('button', { name: /^(Log in|Sign in|Đăng nhập)$/i });
        if (!(await login.first().isVisible().catch(() => false))) return { ready: true, busy: false };
      }
    }
    return { ready: false, code: 'LOGIN_REQUIRED' };
  }
  async generate(prompt, { timeoutMs = 300000, signal } = {}) {
    if (this.busy) throw new BridgeError('BUSY', 'Một ảnh đang được tạo. Đợi hoàn tất trước khi thử lại.');
    this.busy = true;
    let page;
    const onAbort = () => page?.close().catch(() => {});
    const deadline = Date.now() + timeoutMs;
    try {
      if (!(await this.status(true)).ready) throw new BridgeError('LOGIN_REQUIRED', 'Đăng nhập ChatGPT trong cửa sổ cầu nối trước khi tạo ảnh.');
      page = await this.context.newPage();
      signal?.addEventListener('abort', onAbort, { once: true });
      if (signal?.aborted) throw new BridgeError('INTERRUPTED', 'Tạo ảnh bị ngắt.');
      await page.goto('https://chatgpt.com/?temporary-chat=true', { waitUntil: 'domcontentloaded', timeout: 45000 });
      const composer = page.locator(composerSelector).first();
      await composer.waitFor({ state: 'visible', timeout: 30000 });
      const baseline = await page.locator(assistantSelector).count();
      await composer.fill('Create exactly one original image, square aspect ratio 1:1. Generate the image directly, not a prompt or a written description.\n\n' + prompt);
      const send = page.getByRole('button', { name: /^(Send prompt|Send message|Send|Gửi|Gửi tin nhắn)$/i });
      if (await send.first().isVisible()) await send.first().click();
      else await composer.press('Enter');
      // Submission happens once. A timeout or ambiguous result is never automatically resubmitted.
      while (Date.now() < deadline) {
        if (signal?.aborted || page.isClosed()) throw new BridgeError('INTERRUPTED', 'Tạo ảnh bị ngắt. Kiểm tra cửa sổ ChatGPT trước khi thử lại.');
        const turns = page.locator(assistantSelector);
        if (await turns.count() > baseline) {
          const turn = turns.last();
          const text = await turn.innerText().catch(() => '');
          if (/reached.*(?:limit|image)|image.*limit|too many requests|đã đạt.*giới hạn|hết.*hạn mức/i.test(text))
            throw new BridgeError('QUOTA', 'ChatGPT báo hết hạn mức tạo ảnh.');
          const stop = page.getByRole('button', { name: /Stop (?:generating|streaming)|Dừng/i });
          if (!(await stop.first().isVisible().catch(() => false))) {
            const image = turn.locator('img').filter({ visible: true });
            const count = await image.count();
            for (let index = count - 1; index >= 0; index--) {
              const dimensions = await image.nth(index).evaluate(el => ({ width: el.naturalWidth, height: el.naturalHeight, complete: el.complete }));
              if (!dimensions.complete || dimensions.width < 512 || dimensions.height < 512) continue;
              await image.nth(index).hover();
              const download = turn.getByRole('button', { name: /Download|Save image|Tải xuống|Lưu ảnh/i });
              if (!(await download.first().isVisible().catch(() => false))) continue;
              const [file] = await Promise.all([
                page.waitForEvent('download', { timeout: 20000 }),
                download.first().click(),
              ]);
              const path = await file.path();
              if (!path || await file.failure()) throw new BridgeError('DOWNLOAD_FAILED', 'ChatGPT chưa tải được ảnh.');
              if ((await stat(path)).size > 10 * 1024 * 1024) {
                await unlink(path).catch(() => {});
                throw new BridgeError('INVALID_IMAGE', 'Ảnh tải về vượt 10 MB.');
              }
              const bytes = await readFile(path);
              await unlink(path).catch(() => {});
              if (bytes.length < 100 || bytes.length > 10 * 1024 * 1024) throw new BridgeError('INVALID_IMAGE', 'Ảnh tải về không hợp lệ hoặc vượt 10 MB.');
              return bytes;
            }
          }
        }
        await page.waitForTimeout(1000);
      }
      throw new BridgeError('TIMEOUT', 'Chưa lấy được ảnh sau 5 phút. Kiểm tra ChatGPT; hệ thống không tự gửi lại prompt.');
    } catch (error) {
      if (error instanceof BridgeError) throw error;
      throw new BridgeError('UI_CHANGED', 'Chưa lấy được ảnh từ giao diện ChatGPT. Kiểm tra đăng nhập, model và nút tải ảnh.');
    } finally {
      signal?.removeEventListener('abort', onAbort);
      await page?.close().catch(() => {});
      this.busy = false;
    }
  }
  async close() { this.closed = true; await this.context.close(); }
}
