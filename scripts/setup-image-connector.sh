#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
node --input-type=module <<'JS'
import { randomBytes } from 'node:crypto';
import { readFile, writeFile, mkdir, chmod } from 'node:fs/promises';
await mkdir('.runtime/chatgpt-web-images', { recursive: true, mode: 0o700 });
let env; try { env = await readFile('.env', 'utf8'); } catch (error) { if (error.code !== 'ENOENT') throw error; env = ''; }
const existing = env.match(/^CHATGPT_WEB_IMAGE_TOKEN=(.*)$/m)?.[1]?.trim();
let token = existing;
if (!token || token.length < 32) {
  try { token = (await readFile('.runtime/chatgpt-web-images/token', 'utf8')).trim(); } catch {}
  if (!token || token.length < 32) token = randomBytes(32).toString('hex');
  env = env.replace(/^CHATGPT_WEB_IMAGE_TOKEN=.*\n?/mg, '').trimEnd() + '\nCHATGPT_WEB_IMAGE_TOKEN=' + token + '\n';
  await writeFile('.env', env, { mode: 0o600 }); await chmod('.env', 0o600);
}
await writeFile('.runtime/chatgpt-web-images/token', token, { mode: 0o600 });
await chmod('.env', 0o600);
await chmod('.runtime/chatgpt-web-images/token', 0o600);
console.log('Đã chuẩn bị kết nối ảnh tự động. Không cần API key tạo ảnh.');
JS
