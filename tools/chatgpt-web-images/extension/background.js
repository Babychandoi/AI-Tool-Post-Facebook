const base = 'http://127.0.0.1:58110';
let polling = false;
let working = false;
let stopped = false;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const err = code => Object.assign(new Error(code), { code });
const allowedImage = url => {
  try { const u = new URL(url); return u.protocol === 'https:' && (u.hostname === 'chatgpt.com' || u.hostname.endsWith('.oaiusercontent.com')) && !u.username && !u.password; }
  catch { return false; }
};
async function credentials() { return (await chrome.storage.local.get('connectorToken')).connectorToken; }
async function request(path, data, token = null) {
  token ||= await credentials();
  const response = await fetch(base + path, {
    method: data === undefined ? 'GET' : 'POST',
    headers: { 'X-Connector-Id': chrome.runtime.id, ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(data === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(data === undefined ? {} : { body: JSON.stringify(data) }),
    signal: AbortSignal.timeout(path === '/extension/job' ? 26000 : 15000),
  });
  const body = await response.json();
  if (!response.ok) throw err(body.error?.code || 'UNAVAILABLE');
  return body;
}
async function setState(state) {
  await chrome.storage.local.set({ connectorState: { ...state, updatedAt: Date.now() } });
  await chrome.action.setBadgeText({ text: state.ready ? '✓' : state.phase === 'GENERATING' ? '…' : '!' });
  await chrome.action.setBadgeBackgroundColor({ color: state.ready ? '#23845b' : '#b87518' });
}
async function waitTab(tabId) {
  const deadline = Date.now() + 35000;
  let readySince = 0;
  while (Date.now() < deadline) {
    const tab = await chrome.tabs.get(tabId);
    const target = tab.url || tab.pendingUrl;
    if (target && !target.startsWith('https://chatgpt.com/')) throw err('LOGIN_REQUIRED');
    try {
      const state = await chrome.tabs.sendMessage(tabId, { type: 'PING' });
      if (state?.loaded && tab.status === 'complete') {
        if (!readySince) readySince = Date.now();
        // SSR exposes a plain GET form before the ChatGPT editor is hydrated.
        if (Date.now() - readySince >= 6000) return state;
      } else readySince = 0;
    }
    catch {}
    await delay(700);
  }
  throw err('UI_CHANGED');
}
async function checkSession() {
  let tabs = await chrome.tabs.query({ url: 'https://chatgpt.com/*' });
  for (const tab of tabs) {
    try { const state = await chrome.tabs.sendMessage(tab.id, { type: 'PING' }); if (state?.ready) return true; }
    catch {}
  }
  const { landingTab } = await chrome.storage.session.get('landingTab');
  if (landingTab && tabs.some(tab => tab.id === landingTab)) return false;
  // A clean tab uses the existing browser login, never reads old conversations.
  const tab = await chrome.tabs.create({ url: 'https://chatgpt.com/', active: false });
  await chrome.storage.session.set({ landingTab: tab.id });
  return !!(await waitTab(tab.id)).ready;
}
async function bytesFromUrl(url) {
  if (!allowedImage(url)) throw err('INVALID_IMAGE');
  const response = await fetch(url, { credentials: 'include', signal: AbortSignal.timeout(25000) });
  if (!response.ok || !allowedImage(response.url)) throw err('DOWNLOAD_FAILED');
  const reader = response.body.getReader(); const chunks = []; let length = 0;
  while (true) {
    const { done, value } = await reader.read(); if (done) break;
    length += value.length;
    if (length > 10 * 1024 * 1024) { await reader.cancel(); throw err('INVALID_IMAGE'); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(length); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  let text = ''; for (let i = 0; i < bytes.length; i += 32768) text += String.fromCharCode(...bytes.subarray(i, i + 32768));
  return btoa(text);
}
async function runJob(job) {
  working = true;
  let tab;
  let previousTab;
  let success = false;
  const pulse = setInterval(() => request('/extension/heartbeat', { ready: true, phase: 'GENERATING' }).catch(() => {}), 10000);
  try {
    const { activeJob } = await chrome.storage.session.get('activeJob');
    // A service-worker restart cannot cause a second prompt submission.
    if (activeJob) throw err('INTERRUPTED');
    await chrome.storage.session.set({ activeJob: { id: job.id, startedAt: Date.now() } });
    [previousTab] = await chrome.tabs.query({ active: true, currentWindow: true });
    // ChatGPT defers menu/gallery rendering in an inactive tab.
    tab = await chrome.tabs.create({ url: 'https://chatgpt.com/#ai-marketing-image=' + job.id, active: true });
    await chrome.storage.session.set({ activeJob: { id: job.id, tabId: tab.id, startedAt: Date.now() } });
    if (!(await waitTab(tab.id)).ready) throw err('LOGIN_REQUIRED');
    await setState({ ready: true, phase: 'GENERATING' });
    const result = await chrome.tabs.sendMessage(tab.id, { type: 'GENERATE_IMAGE', job });
    if (result.error) throw Object.assign(err(result.error.code), { reset_at: result.error.reset_at, stage: result.error.stage, button_labels: result.error.button_labels });
    const b64 = result.b64_json || await bytesFromUrl(result.url);
    await request('/extension/result', { id: job.id, b64_json: b64 });
    success = true;
    await chrome.storage.local.remove('lastFailure');
    await setState({ ready: true, phase: 'IDLE', lastCompletedAt: Date.now() });
  } catch (error) {
    const code = ['LOGIN_REQUIRED','QUOTA','TIMEOUT','UI_CHANGED','INTERRUPTED','DOWNLOAD_FAILED','INVALID_IMAGE'].includes(error.code) ? error.code : 'UI_CHANGED';
    await chrome.storage.local.set({ lastFailure: { code, stage: error.stage || 'OPENING_TAB', button_labels: error.button_labels || [], at: Date.now() } });
    if (code === 'QUOTA') {
      const quotaResetAt = Number.isFinite(error.reset_at) && error.reset_at > Date.now() && error.reset_at < Date.now() + 172800000 ? error.reset_at : Date.now() + 3600000;
      await chrome.storage.local.set({ quotaResetAt });
      await request('/extension/heartbeat', { ready: false, phase: 'QUOTA' }).catch(() => {});
    }
    await request('/extension/result', { id: job.id, error: { code } }).catch(() => {});
    await setState({ ready: !['LOGIN_REQUIRED','QUOTA'].includes(code), phase: code === 'QUOTA' ? 'QUOTA' : 'ERROR', error: code, tabId: tab?.id });
  } finally {
    clearInterval(pulse); working = false;
    await chrome.storage.session.remove('activeJob');
    // Keep failed tabs for diagnosis. Never close a user's existing tab.
    if (success && tab) {
      const wasActive = await chrome.tabs.get(tab.id).then(t => t.active).catch(() => false);
      await chrome.tabs.remove(tab.id).catch(() => {});
      if (wasActive && previousTab) await chrome.tabs.update(previousTab.id, { active: true }).catch(() => {});
    }
  }
}
async function poll() {
  // Set the guard before any await: startup/install/alarm can arrive together.
  if (polling || working || stopped) return;
  polling = true;
  try {
    if (!(await credentials())) return;
    while (!stopped && await credentials()) {
      const { quotaResetAt } = await chrome.storage.local.get('quotaResetAt');
      if (quotaResetAt > Date.now()) {
        await request('/extension/heartbeat', { ready: false, phase: 'QUOTA' });
        await setState({ ready: false, phase: 'QUOTA', error: 'QUOTA', quotaResetAt });
        break;
      }
      const ready = await checkSession();
      await request('/extension/heartbeat', { ready, phase: ready ? 'IDLE' : 'LOGIN_REQUIRED' });
      await setState({ ready, phase: ready ? 'IDLE' : 'LOGIN_REQUIRED' });
      if (!ready) break;
      const { job } = await request('/extension/job');
      if (job) await runJob(job);
    }
  } catch (error) {
    await setState({ ready: false, phase: 'DISCONNECTED', error: error.code || 'BRIDGE_OFFLINE' });
  } finally { polling = false; }
}
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  // Pairing and token operations are accepted only from this extension's popup.
  if (sender.id !== chrome.runtime.id || sender.url !== chrome.runtime.getURL('popup.html')) return;
  if (message.type === 'PAIR') {
    (async () => {
      try {
        const { token } = await request('/extension/pair', { code: message.code });
        await chrome.storage.local.set({ connectorToken: token }); stopped = false;
        respond({ ok: true }); poll();
      } catch (error) { respond({ ok: false, code: error.code || 'BRIDGE_OFFLINE' }); }
    })(); return true;
  }
  if (message.type === 'CHECK') { stopped = false; chrome.storage.local.remove('quotaResetAt').then(poll); respond({ ok: true }); }
  if (message.type === 'DISCONNECT') {
    stopped = true; chrome.storage.local.remove('connectorToken').then(() => setState({ ready: false, phase: 'DISCONNECTED' })).then(() => respond({ ok: true })); return true;
  }
});
chrome.alarms.onAlarm.addListener(alarm => { if (alarm.name === 'image-connector') poll(); });
chrome.runtime.onStartup.addListener(() => { stopped = false; poll(); });
chrome.runtime.onInstalled.addListener(() => { chrome.alarms.create('image-connector', { periodInMinutes: 0.5 }); poll(); });
chrome.storage.local.setAccessLevel({ accessLevel: 'TRUSTED_CONTEXTS' });
chrome.alarms.create('image-connector', { periodInMinutes: 0.5 });
poll();
