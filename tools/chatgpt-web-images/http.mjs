import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';

export function createBridgeServer(browser, token, { connector } = {}) {
  function reply(res, status, value) {
    if (res.destroyed) return;
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(value));
  }
  async function jsonBody(req, limit) {
    const chunks = []; let length = 0;
    for await (const chunk of req) {
      length += chunk.length;
      if (length > limit) throw Object.assign(new Error(), { code: 'TOO_LARGE' });
      chunks.push(chunk);
    }
    try { return JSON.parse(Buffer.concat(chunks).toString()); }
    catch { throw Object.assign(new Error(), { code: 'INVALID_JSON' }); }
  }
  return createServer(async (req, res) => {
    const extensionRoute = connector && req.url?.startsWith('/extension/');
    if ((req.headers.origin && !(extensionRoute && req.headers.origin === connector.origin))
        || !/^(127\.0\.0\.1|localhost|host\.docker\.internal|image-bridge)(:\d+)?$/.test(req.headers.host || ''))
      return reply(res, 403, { error: { code: 'LOCAL_ONLY', message: 'Chỉ hỗ trợ kết nối local.' } });
    if (extensionRoute) {
      if (req.headers['x-connector-id'] !== connector.extensionId)
        return reply(res, 403, { error: { code: 'CONNECTOR_ONLY' } });
      try {
        if (req.url === '/extension/pair' && req.method === 'POST')
          return reply(res, 200, await connector.pairClient((await jsonBody(req, 1000)).code));
        if (!connector.authorized(req)) return reply(res, 401, { error: { code: 'UNAUTHORIZED' } });
        if (req.url === '/extension/heartbeat' && req.method === 'POST') {
          connector.touch(await jsonBody(req, 1000));
          return reply(res, 200, { ok: true });
        }
        if (req.url === '/extension/job' && req.method === 'GET') {
          connector.touch();
          const abort = new AbortController();
          res.once('close', () => { if (!res.writableEnded) abort.abort(); });
          return reply(res, 200, { job: await connector.nextJob(abort.signal) });
        }
        if (req.url === '/extension/result' && req.method === 'POST') {
          connector.touch(); connector.result(await jsonBody(req, 14 * 1024 * 1024 + 2048));
          return reply(res, 200, { ok: true });
        }
        return reply(res, 404, { error: { code: 'NOT_FOUND' } });
      } catch (error) {
        const code = ['PAIRING_LOCKED','PAIRING_INVALID','STALE_JOB','INVALID_IMAGE','TOO_LARGE','INVALID_JSON','BUSY'].includes(error.code) ? error.code : 'UNAVAILABLE';
        return reply(res, 400, { error: { code } });
      }
    }
    if (req.url === '/health' && req.method === 'GET') {
      try { return reply(res, 200, { service: 'marketing-chatgpt-web-images', ...(await browser.status()) }); }
      catch { return reply(res, 200, { service: 'marketing-chatgpt-web-images', ready: false, code: 'BROWSER_CLOSED' }); }
    }
    const supplied = Buffer.from(req.headers.authorization || '');
    const expected = Buffer.from('Bearer ' + token);
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected))
      return reply(res, 401, { error: { code: 'UNAUTHORIZED', message: 'Sai token cầu nối local.' } });
    if (connector && req.url === '/connector/pairing' && req.method === 'POST') {
      try { return reply(res, 200, connector.pairing()); }
      catch { return reply(res, 409, { error: { code: 'BUSY' } }); }
    }
    if (req.url !== '/v1/images/generations' || req.method !== 'POST') return reply(res, 404, { error: { code: 'NOT_FOUND' } });
    if (browser.busy) return reply(res, 409, { error: { code: 'BUSY', message: 'Một ảnh đang được tạo.' } });
    const abort = new AbortController();
    res.once('close', () => { if (!res.writableEnded) abort.abort(); });
    try {
      let payload;
      try { payload = await jsonBody(req, 32000); }
      catch (error) { return reply(res, error.code === 'TOO_LARGE' ? 413 : 400, { error: { code: error.code } }); }
      if (typeof payload.prompt !== 'string' || !payload.prompt.trim() || payload.prompt.length > 14000)
        return reply(res, 400, { error: { code: 'INVALID_PROMPT' } });
      const bytes = await browser.generate(payload.prompt, { signal: abort.signal });
      reply(res, 200, { data: [{ b64_json: bytes.toString('base64') }], provider: 'CHATGPT_WEB', billing: 'CHATGPT_PLAN', generation_mode: browser.mode || 'DEDICATED_BROWSER' });
    } catch (error) {
      const known = ['LOGIN_REQUIRED', 'BUSY', 'QUOTA', 'TIMEOUT', 'UI_CHANGED', 'INTERRUPTED', 'DOWNLOAD_FAILED', 'INVALID_IMAGE'].includes(error.code);
      reply(res, error.code === 'BUSY' ? 409 : 503, { error: {
        code: known ? error.code : 'UNAVAILABLE',
        message: known ? error.message : 'Cầu nối ChatGPT Web chưa sẵn sàng.',
      } });
    }
  });
}
