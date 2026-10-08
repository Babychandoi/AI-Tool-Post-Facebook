import test from 'node:test';
import assert from 'node:assert/strict';
import { createBridgeServer } from '../http.mjs';

const token = 'test-token-with-more-than-32-characters';
async function start(t, browser) {
  const server = createBridgeServer(browser, token);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  return `http://127.0.0.1:${server.address().port}`;
}
const request = prompt => ({ method: 'POST', headers: { Authorization: 'Bearer ' + token }, body: JSON.stringify({ prompt }) });

test('health reports logged-out state; unauthenticated and cross-origin requests never submit prompts', async t => {
  let calls = 0;
  const url = await start(t, { status: async () => ({ ready: false, code: 'LOGIN_REQUIRED' }), generate: async () => { calls++; } });
  assert.equal((await (await fetch(url + '/health')).json()).ready, false);
  assert.equal((await fetch(url + '/v1/images/generations', { method: 'POST', body: '{}' })).status, 401);
  const cross = request('make an image'); cross.headers.Origin = 'https://example.com';
  assert.equal((await fetch(url + '/v1/images/generations', cross)).status, 403);
  assert.equal(calls, 0);
});
test('success returns exact downloaded bytes; invalid prompts and busy requests are rejected', async t => {
  const browser = { busy: false, status: async () => ({ ready: true }), generate: async () => Buffer.from('fixture-image') };
  const url = await start(t, browser);
  const response = await fetch(url + '/v1/images/generations', request('make an image'));
  assert.equal(response.status, 200);
  assert.equal(Buffer.from((await response.json()).data[0].b64_json, 'base64').toString(), 'fixture-image');
  assert.equal((await fetch(url + '/v1/images/generations', request(''))).status, 400);
  browser.busy = true;
  assert.equal((await fetch(url + '/v1/images/generations', request('make an image'))).status, 409);
});
test('provider failures return bounded error codes and hide unknown exception details', async t => {
  const url = await start(t, { generate: async () => { throw new Error('private login material'); } });
  const response = await fetch(url + '/v1/images/generations', request('make an image'));
  assert.equal(response.status, 503);
  const body = await response.text();
  assert.ok(body.includes('UNAVAILABLE'));
  assert.ok(!body.includes('private login material'));
});
