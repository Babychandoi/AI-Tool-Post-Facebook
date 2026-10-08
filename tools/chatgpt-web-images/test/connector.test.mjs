import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ExtensionConnector } from '../connector.mjs';
import { createBridgeServer } from '../http.mjs';

const extensionId = 'gedjjpmaogkijdpblbekoofeahdbmiih';
async function setup(t) {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'marketing-connector-'));
  const connector = await new ExtensionConnector({ extensionId, stateDirectory }).init();
  t.after(() => { connector.close(); return rm(stateDirectory, { recursive: true }); });
  return connector;
}
test('pairing is one-time, bounded, survives restart and is not readiness', async t => {
  const connector = await setup(t);
  const { code } = connector.pairing();
  await assert.rejects(connector.pairClient('invalid'), error => error.code === 'PAIRING_INVALID');
  const { token } = await connector.pairClient(code);
  assert.equal((await connector.status()).ready, false);
  await assert.rejects(connector.pairClient(code), error => error.code === 'PAIRING_INVALID');
  const restarted = await new ExtensionConnector({ extensionId, stateDirectory: connector.stateDirectory }).init();
  assert.equal(restarted.authorized({ headers: { authorization: 'Bearer ' + token } }), true);
  assert.equal((await restarted.status()).ready, false);
  connector.pairing();
  for (let i = 0; i < 5; i++) await assert.rejects(connector.pairClient('invalid'));
  await assert.rejects(connector.pairClient('invalid'), error => error.code === 'PAIRING_LOCKED');
});
test('image request is delivered once, rejects stale responses, and returns exact bytes', async t => {
  const connector = await setup(t);
  connector.touch({ ready: true, phase: 'IDLE' });
  const result = connector.generate('create test image');
  const job = await connector.nextJob();
  assert.equal(job.prompt, 'create test image');
  assert.throws(() => connector.result({ id: 'other', b64_json: 'AAAA' }), error => error.code === 'STALE_JOB');
  const abort = new AbortController(); const poll = connector.nextJob(abort.signal); abort.abort();
  assert.equal(await poll, null);
  await assert.rejects(connector.generate('second'), error => error.code === 'BUSY');
  const image = Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), Buffer.alloc(256)]);
  connector.result({ id: job.id, b64_json: image.toString('base64') });
  assert.deepEqual(await result, image);
  assert.equal(connector.busy, false);
});
test('offline, timeout, quota and malformed output never become image success', async t => {
  const connector = await setup(t);
  await assert.rejects(connector.generate('offline'), error => error.code === 'LOGIN_REQUIRED');
  connector.touch({ ready: true });
  await assert.rejects(connector.generate('timed out', { timeoutMs: 10 }), error => error.code === 'TIMEOUT');
  const pending = connector.generate('quota'); const job = await connector.nextJob();
  connector.result({ id: job.id, error: { code: 'QUOTA' } });
  await assert.rejects(pending, error => error.code === 'QUOTA');
  const abort = new AbortController(); const invalid = connector.generate('invalid', { signal: abort.signal });
  const invalidJob = await connector.nextJob();
  assert.throws(() => connector.result({ id: invalidJob.id, b64_json: Buffer.from('<html>' + 'x'.repeat(256)).toString('base64') }), error => error.code === 'INVALID_IMAGE');
  abort.abort(); await assert.rejects(invalid, error => error.code === 'INTERRUPTED');
});
test('HTTP connector requires admin pairing, specific extension and a distinct client token', async t => {
  const connector = await setup(t);
  const admin = 'admin-token-with-more-than-32-characters';
  const server = createBridgeServer(connector, admin, { connector });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = 'http://127.0.0.1:' + server.address().port;
  const post = (path, body, headers = {}) => fetch(base + path, { method: 'POST', headers, body: JSON.stringify(body) });
  assert.equal((await post('/connector/pairing', {})).status, 401);
  const pairing = await (await post('/connector/pairing', {}, { Authorization: 'Bearer ' + admin })).json();
  assert.equal((await post('/extension/pair', { code: pairing.code }, { 'X-Connector-Id': extensionId, Origin: 'https://evil.example' })).status, 403);
  const client = await (await post('/extension/pair', { code: pairing.code }, { 'X-Connector-Id': extensionId, Origin: connector.origin })).json();
  assert.notEqual(client.token, admin);
  const headers = { 'X-Connector-Id': extensionId, Authorization: 'Bearer ' + client.token, Origin: connector.origin };
  assert.equal((await post('/extension/heartbeat', { ready: true, phase: 'IDLE' }, headers)).status, 200);
  assert.equal((await (await fetch(base + '/health')).json()).ready, true);
  assert.equal((await post('/v1/images/generations', { prompt: 'not admin' }, headers)).status, 403);
  assert.equal((await post('/connector/pairing', {}, headers)).status, 403);
});
