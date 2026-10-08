import { randomBytes, randomInt, createHash, timingSafeEqual } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

const hash = value => createHash('sha256').update(value).digest('hex');
const equal = (a, b) => {
  const aa = Buffer.from(a || ''), bb = Buffer.from(b || '');
  return aa.length === bb.length && timingSafeEqual(aa, bb);
};
const failure = (code, message) => Object.assign(new Error(message), { code });
const knownErrors = new Set(['LOGIN_REQUIRED', 'BUSY', 'QUOTA', 'TIMEOUT', 'UI_CHANGED', 'INTERRUPTED', 'DOWNLOAD_FAILED', 'INVALID_IMAGE']);

export class ExtensionConnector {
  constructor({ extensionId, stateDirectory, now = Date.now }) {
    this.extensionId = extensionId;
    this.origin = 'chrome-extension://' + extensionId;
    this.stateDirectory = stateDirectory;
    this.now = now;
    this.mode = 'BROWSER_EXTENSION';
    this.busy = false;
    this.lastSeen = 0;
    this.loggedIn = false;
    this.phase = 'DISCONNECTED';
    this.clientHash = '';
    this.pair = null;
    this.attempts = [];
    this.pending = null;
    this.pollWaiter = null;
  }
  async init() {
    await mkdir(this.stateDirectory, { recursive: true, mode: 0o700 });
    try {
      const saved = JSON.parse(await readFile(resolve(this.stateDirectory, 'connector.json'), 'utf8'));
      if (saved.extensionId === this.extensionId) this.clientHash = saved.clientHash || '';
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
    return this;
  }
  async status() {
    const connected = this.lastSeen > 0 && this.now() - this.lastSeen < 45000;
    return {
      ready: connected && this.loggedIn,
      connected,
      paired: !!this.clientHash,
      mode: this.mode,
      busy: this.busy,
      phase: connected ? this.phase : 'DISCONNECTED',
      code: !connected ? 'CONNECTOR_OFFLINE' : !this.loggedIn ? this.phase === 'QUOTA' ? 'QUOTA' : 'LOGIN_REQUIRED' : null,
      extension_id: this.extensionId,
    };
  }
  pairing() {
    if (this.busy) throw failure('BUSY', 'Đợi ảnh đang tạo hoàn tất trước khi ghép nối lại.');
    const code = String(randomInt(100000, 1000000));
    this.pair = { codeHash: hash(code), expires: this.now() + 120000 };
    this.attempts = [];
    return { code, expires_in: 120, extension_id: this.extensionId };
  }
  async pairClient(code) {
    this.attempts = this.attempts.filter(at => this.now() - at < 120000);
    if (this.attempts.length >= 5) throw failure('PAIRING_LOCKED', 'Mã ghép nối đã bị khóa. Tạo mã mới trong ứng dụng.');
    this.attempts.push(this.now());
    if (!this.pair || this.pair.expires < this.now() || !equal(hash(String(code)), this.pair.codeHash))
      throw failure('PAIRING_INVALID', 'Mã không đúng hoặc đã hết hạn. Tạo mã mới trong ứng dụng.');
    const token = randomBytes(32).toString('hex');
    this.clientHash = hash(token);
    this.pair = null;
    this.lastSeen = 0;
    this.loggedIn = false;
    await writeFile(resolve(this.stateDirectory, 'connector.json'), JSON.stringify({ extensionId: this.extensionId, clientHash: this.clientHash }), { mode: 0o600 });
    return { token, extension_id: this.extensionId };
  }
  authorized(req) {
    return !!this.clientHash && equal(hash((req.headers.authorization || '').replace(/^Bearer /, '')), this.clientHash);
  }
  touch({ ready, phase } = {}) {
    this.lastSeen = this.now();
    if (typeof ready === 'boolean') this.loggedIn = ready;
    if (typeof phase === 'string' && /^[A-Z_]{1,32}$/.test(phase)) this.phase = phase;
  }
  async nextJob(signal) {
    if (this.pending && !this.pending.claimed) {
      this.pending.claimed = true;
      return { id: this.pending.id, prompt: this.pending.prompt, deadline: this.pending.deadline };
    }
    if (this.pollWaiter) throw failure('BUSY', 'Đã có một kết nối chờ ảnh.');
    return new Promise(resolvePromise => {
      const finish = job => {
        clearTimeout(timer);
        signal?.removeEventListener('abort', aborted);
        if (this.pollWaiter === finish) this.pollWaiter = null;
        resolvePromise(job);
      };
      const aborted = () => finish(null);
      const timer = setTimeout(() => finish(null), 20000);
      this.pollWaiter = finish;
      signal?.addEventListener('abort', aborted, { once: true });
      if (signal?.aborted) finish(null);
    });
  }
  async generate(prompt, { signal, timeoutMs = 285000 } = {}) {
    if (this.busy) throw failure('BUSY', 'Một ảnh đang được tạo.');
    if (!(await this.status()).ready) throw failure('LOGIN_REQUIRED', 'Kết nối tiện ích và đăng nhập ChatGPT trong Cốc Cốc trước khi tạo ảnh.');
    this.busy = true;
    this.phase = 'WAITING_BROWSER';
    try {
      return await new Promise((resolvePromise, reject) => {
        const id = randomBytes(16).toString('hex');
        const done = (error, bytes) => {
          clearTimeout(timer);
          signal?.removeEventListener('abort', aborted);
          this.pending = null;
          error ? reject(error) : resolvePromise(bytes);
        };
        const aborted = () => done(failure('INTERRUPTED', 'Yêu cầu ảnh đã dừng. Không tự gửi lại prompt.'));
        const timer = setTimeout(() => done(failure('TIMEOUT', 'Chưa lấy được ảnh sau gần 5 phút. Kiểm tra ChatGPT; prompt không tự gửi lại.')), timeoutMs);
        this.pending = { id, prompt, deadline: this.now() + timeoutMs, done, claimed: false };
        signal?.addEventListener('abort', aborted, { once: true });
        if (signal?.aborted) return aborted();
        if (this.pollWaiter) {
          this.pending.claimed = true;
          this.pollWaiter({ id, prompt, deadline: this.pending.deadline });
        }
      });
    } finally { this.busy = false; this.phase = 'IDLE'; }
  }
  result(data) {
    const job = this.pending;
    if (!job || !equal(data.id, job.id) || !job.claimed) throw failure('STALE_JOB', 'Yêu cầu ảnh đã hết hiệu lực.');
    if (data.error) {
      const code = knownErrors.has(data.error.code) ? data.error.code : 'UI_CHANGED';
      job.done(failure(code, 'Tiện ích chưa tạo được ảnh: ' + code + '. Kiểm tra tab ChatGPT của yêu cầu này.'));
      return;
    }
    if (typeof data.b64_json !== 'string' || data.b64_json.length > 14 * 1024 * 1024 || !/^[A-Za-z0-9+/]+={0,2}$/.test(data.b64_json))
      throw failure('INVALID_IMAGE', 'Dữ liệu ảnh không hợp lệ.');
    const bytes = Buffer.from(data.b64_json, 'base64');
    const png = bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]));
    const jpeg = bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
    if ((!png && !jpeg) || bytes.length < 100 || bytes.length > 10 * 1024 * 1024)
      throw failure('INVALID_IMAGE', 'Chỉ nhận ảnh PNG/JPEG dưới 10 MB.');
    job.done(null, bytes);
  }
  close() {
    this.pollWaiter?.(null);
    this.pending?.done(failure('INTERRUPTED', 'Cầu nối đã dừng.'));
  }
}
