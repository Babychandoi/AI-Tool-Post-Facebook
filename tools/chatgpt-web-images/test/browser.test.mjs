import test from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { existsSync } from 'node:fs';
import { ImageBrowser } from '../browser.mjs';

const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
async function setup(t, output) {
  const browser = await chromium.launch({ headless: true, ...(existsSync(chrome) ? { executablePath: chrome } : {}) });
  const context = await browser.newContext({ acceptDownloads: true });
  await context.route('https://chatgpt.com/**', route => route.fulfill({ contentType: 'text/html', body: `
    <main><div data-message-author-role="assistant"><img width="1024" height="1024" alt="old response"></div>
    <textarea id="prompt-textarea"></textarea><button aria-label="Send prompt" onclick="send()">Send</button></main>
    <script>
      window.submissions=0;
      function send(){
        window.submissions++;
        const turn=document.createElement('div'); turn.dataset.messageAuthorRole='assistant';
        document.querySelector('main').append(turn);
        ${output ? `
        const canvas=document.createElement('canvas'); canvas.width=512; canvas.height=512;
        canvas.getContext('2d').fillRect(0,0,512,512);
        const img=new Image(); img.src=canvas.toDataURL('image/png'); turn.append(img);
        const button=document.createElement('button'); button.setAttribute('aria-label','Download'); turn.append(button);
        button.onclick=()=>canvas.toBlob(blob=>{ const link=document.createElement('a'); link.href=URL.createObjectURL(blob); link.download='new-image.png'; link.click(); });
        ` : `turn.textContent='Only text returned, no image.';`}
      }
    </script>` }));
  const page = await context.newPage(); await page.goto('https://chatgpt.com/');
  const adapter = new ImageBrowser(context);
  t.after(() => browser.close());
  return adapter;
}
test('downloads an image from the new assistant response and releases its page', async t => {
  const adapter = await setup(t, true);
  const bytes = await adapter.generate('create a fixture image', { timeoutMs: 5000 });
  assert.equal(bytes.subarray(1, 4).toString(), 'PNG');
  assert.equal(adapter.busy, false);
  assert.equal(adapter.context.pages().length, 1);
});
test('does not reuse an old image or resubmit when a new response contains only text', async t => {
  const adapter = await setup(t, false);
  const pending = adapter.generate('create a fixture image', { timeoutMs: 1200 });
  await assert.rejects(adapter.generate('concurrent request'), error => error.code === 'BUSY');
  await assert.rejects(pending, error => error.code === 'TIMEOUT');
  assert.equal(adapter.busy, false);
  assert.equal(adapter.context.pages().length, 1);
});
