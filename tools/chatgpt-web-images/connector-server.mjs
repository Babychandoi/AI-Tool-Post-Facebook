import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { randomBytes, createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ExtensionConnector } from './connector.mjs';
import { createBridgeServer } from './http.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const directory = process.env.CHATGPT_WEB_IMAGE_STATE_DIR || resolve(root, '.runtime/chatgpt-web-images');
await mkdir(directory, { recursive: true, mode: 0o700 });
const tokenPath = process.env.CHATGPT_WEB_IMAGE_TOKEN_FILE || resolve(directory, 'token');
let token = process.env.CHATGPT_WEB_IMAGE_TOKEN;
if (!token) {
  try { token = (await readFile(tokenPath, 'utf8')).trim(); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    token = randomBytes(32).toString('hex');
    await writeFile(tokenPath, token, { mode: 0o600, flag: 'wx' });
  }
}
if (token.length < 32) throw new Error('Token cầu nối cần ít nhất 32 ký tự.');
const manifest = JSON.parse(await readFile(new URL('./extension/manifest.json', import.meta.url), 'utf8'));
const extensionId = createHash('sha256').update(Buffer.from(manifest.key, 'base64')).digest('hex').slice(0, 32).replace(/[0-9a-f]/g, c => String.fromCharCode(97 + parseInt(c, 16)));
const connector = await new ExtensionConnector({ extensionId, stateDirectory: directory }).init();
const server = createBridgeServer(connector, token, { connector });
server.requestTimeout = 330000;
server.listen(58110, process.env.CHATGPT_WEB_IMAGE_BIND || '127.0.0.1', () => console.log('Cầu nối ảnh tự động sẵn sàng; ghép tiện ích Cốc Cốc trong Đăng tự động.'));
function stop() { connector.close(); server.close(() => process.exit(0)); }
process.once('SIGINT', stop);
process.once('SIGTERM', stop);
