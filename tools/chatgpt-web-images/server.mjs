import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { resolve } from 'node:path';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createBrowser } from './browser.mjs';
import { createBridgeServer } from './http.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const directory = resolve(root, '.runtime/chatgpt-web-images');
await mkdir(directory, { recursive: true, mode: 0o700 });
const tokenPath = resolve(directory, 'token');
let token = process.env.CHATGPT_WEB_IMAGE_TOKEN;
if (!token) {
  if (existsSync(tokenPath)) token = (await readFile(tokenPath, 'utf8')).trim();
  else {
    token = randomBytes(32).toString('hex');
    await writeFile(tokenPath, token, { mode: 0o600, flag: 'wx' });
  }
}
if (token.length < 32) throw new Error('Token cầu nối cần ít nhất 32 ký tự.');
const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const executable = process.env.CHATGPT_WEB_IMAGE_BROWSER || (existsSync(chrome) ? chrome : undefined);
const browser = await createBrowser(resolve(directory, 'profile'), executable);
const server = createBridgeServer(browser, token);
server.requestTimeout = 330000;
server.listen(58110, '127.0.0.1', () => {
  console.log('Cầu nối tạo ảnh: http://127.0.0.1:58110. Đăng nhập ChatGPT trong cửa sổ vừa mở.');
  console.log('Chọn ChatGPT Web (thử nghiệm) trong Đăng tự động → Ảnh cho bài viết.');
});
async function stop() { server.close(); await browser.close(); process.exit(0); }
process.once('SIGINT', stop);
process.once('SIGTERM', stop);
