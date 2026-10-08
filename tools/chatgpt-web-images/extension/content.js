(() => {
  const composerSelector = '#prompt-textarea, [contenteditable="true"][role="textbox"], textarea[placeholder]';
  const assistantSelector = '[data-message-author-role="assistant"], [data-turn="assistant"]';
  const assistantTurns = () => [...new Set([
    ...document.querySelectorAll(assistantSelector),
    ...[...document.querySelectorAll('[data-conversation-role="assistant"]')].map(el => el.parentElement),
  ])];
  const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
  const visible = el => el && el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0;
  const buttons = () => [...document.querySelectorAll('button')].filter(visible);
  // SVG icons embed CSS in textContent; innerText reflects the displayed caption.
  const name = el => el.getAttribute('aria-label') || el.getAttribute('title') || el.innerText.trim();
  const composer = () => [...document.querySelectorAll(composerSelector)].find(visible);
  const ready = () => !!composer()?.isContentEditable && document.readyState === 'complete' && !buttons().some(el => /^(Log in|Sign in|Đăng nhập)$/i.test(name(el)));
  const fail = (code, details = {}) => { throw Object.assign(new Error(code), { code, ...details }); };
  function quotaReset(text) {
    const time = text.match(/(?:lúc|at)\s+(\d{1,2}):(\d{2})/i);
    if (!time) return Date.now() + 3600000;
    const reset = new Date(); reset.setHours(Number(time[1]), Number(time[2]), 0, 0);
    if (/ngày mai|tomorrow/i.test(text) || reset.getTime() <= Date.now()) reset.setDate(reset.getDate() + 1);
    return reset.getTime();
  }
  let activeId = null;
  let stage = 'STARTING';
  async function waitFor(fn, deadline, code = 'TIMEOUT') {
    while (Date.now() < deadline) { const result = fn(); if (result) return result; await delay(500); }
    fail(code);
  }
  function fill(el, text) {
    el.focus();
    if (el.tagName === 'TEXTAREA') {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(el, text);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    } else {
      document.execCommand('selectAll', false);
      document.execCommand('insertText', false, text);
      el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
    }
  }
  async function selectImageMode(deadline) {
    const isImage = el => /^(Tạo ảnh|Create images?|Create an image)/i.test(name(el));
    let create = buttons().find(isImage);
    if (!create) {
      const add = buttons().find(el => /^(Thêm tệp và nội dung khác|Add files and more|Add photos and files)$/i.test(name(el)));
      if (add) {
        add.click();
        create = await waitFor(() => buttons().find(isImage), Math.min(deadline, Date.now() + 15000), 'UI_CHANGED');
      }
    }
    if (create) { create.click(); await delay(700); }
  }
  async function readLocalImage(url) {
    if (!url.startsWith('blob:https://chatgpt.com/') && !/^data:image\/(png|jpeg);base64,/.test(url)) fail('INVALID_IMAGE');
    const response = await fetch(url); const blob = await response.blob();
    if (blob.size < 100 || blob.size > 10 * 1024 * 1024) fail('INVALID_IMAGE');
    return new Promise((resolve, reject) => {
      const reader = new FileReader(); reader.onload = () => resolve(reader.result.split(',')[1]); reader.onerror = () => reject(Object.assign(new Error(), { code: 'DOWNLOAD_FAILED' })); reader.readAsDataURL(blob);
    });
  }
  async function generate(job) {
    if (activeId) fail('BUSY');
    if (!/^[a-f0-9]{32}$/.test(job.id) || typeof job.prompt !== 'string' || job.prompt.length > 14000) fail('UI_CHANGED');
    // This job must own a clean new tab. Never put a prompt into an old chat.
    if (location.pathname !== '/' || location.hash !== '#ai-marketing-image=' + job.id) fail('UI_CHANGED');
    activeId = job.id;
    const deadline = Math.min(job.deadline - 15000, Date.now() + 255000);
    if (!ready()) fail('LOGIN_REQUIRED');
    const prior = new Set(assistantTurns());
    composer()?.closest('form')?.addEventListener('submit', event => event.preventDefault(), { capture: true });
    const input = composer(); if (!input) fail('UI_CHANGED');
    const form = input.closest('form');
    // Never let an unhydrated HTML form place the prompt in a GET URL.
    form?.addEventListener('submit', event => event.preventDefault(), { capture: true });
    fill(input, 'Create exactly one original square image. Generate the image directly, not a written prompt.\n\n' + job.prompt);
    await delay(500);
    // The image tool is an editor chip. Selecting all after adding it deletes it.
    stage = 'SELECTING_IMAGE_TOOL';
    await selectImageMode(deadline);
    composer()?.closest('form')?.addEventListener('submit', event => event.preventDefault(), { capture: true });
    stage = 'SENDING_PROMPT';
    const send = await waitFor(() => buttons().find(el => !el.disabled && /^(Send prompt|Send message|Send|Gửi câu lệnh|Gửi tin nhắn|Gửi)$/i.test(name(el))), Math.min(deadline, Date.now() + 10000), 'UI_CHANGED');
    send.click(); // Submit once. Ambiguous results never trigger a second submission.
    stage = 'WAITING_IMAGE';
    let finishedAt = 0;
    while (Date.now() < deadline) {
      const turns = assistantTurns().filter(el => !prior.has(el));
      for (const turn of turns) {
        const text = turn.innerText || '';
        if (/reached.*(?:limit|image)|image.*limit|too many requests|đã đạt.*giới hạn|hết.*hạn mức|Tạo ảnh này sau|Create this image later|mức sử dụng.*đặt lại/i.test(text)) fail('QUOTA', { reset_at: quotaReset(text) });
        const generating = buttons().some(el => /^(Stop|Stop generating|Stop streaming|Dừng trả lời|Dừng tạo|Ngừng)/i.test(name(el))) || !!turn.querySelector('[role="progressbar"]');
        const images = [...turn.querySelectorAll('img')].filter(el => el.complete && el.naturalWidth >= 512 && el.naturalHeight >= 512 && visible(el));
        if (images.length && !generating) {
          if (!finishedAt) finishedAt = Date.now();
          if (Date.now() - finishedAt < 2000) continue;
          const url = images.at(-1).currentSrc || images.at(-1).src;
          if (url.startsWith('blob:') || url.startsWith('data:')) return { b64_json: await readLocalImage(url) };
          return { url };
        }
      }
      await delay(1000);
    }
    fail('TIMEOUT');
  }
  chrome.runtime.onMessage.addListener((message, sender, respond) => {
    if (sender.id !== chrome.runtime.id) return;
    if (message.type === 'PING') { respond({ loaded: ready(), ready: ready() }); return; }
    if (message.type === 'GENERATE_IMAGE') {
      generate(message.job).then(respond).catch(error => respond({ error: { code: error.code || 'UI_CHANGED', stage, ...(error.reset_at ? { reset_at: error.reset_at } : {}), ...(error.code === 'UI_CHANGED' ? { button_labels: buttons().map(name).filter(label => /create|image|tạo|ảnh|send|gửi|add|thêm/i.test(label)).map(label => label.slice(0, 120)).slice(-12) } : {}) } }));
      return true;
    }
  });
})();
