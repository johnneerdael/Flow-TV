(() => {
  'use strict';
  const { gcm, randomBytes } = window.nobleCiphers;
  const $ = (id) => document.getElementById(id);
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  const b64u = {
    enc: (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''),
    dec: (text) => Uint8Array.from(atob(text.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((text.length + 3) % 4)), (c) => c.charCodeAt(0)),
  };

  const parts = location.hash.slice(1).split('.');
  if (parts.length !== 2) {
    $('status').textContent = 'Scan the QR code on the TV again.';
    return;
  }
  const sessionId = b64u.dec(parts[0]);
  const key = b64u.dec(parts[1]);
  history.replaceState(null, '', location.pathname);

  const aad = (direction) => {
    const tail = enc.encode(direction);
    const out = new Uint8Array(sessionId.length + tail.length);
    out.set(sessionId);
    out.set(tail, sessionId.length);
    return out;
  };

  // Strictly increasing across page reloads, which the TV enforces against replays.
  let seq = Date.now();
  let requests = Promise.resolve();

  async function fetchBounded(path, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);
    try {
      const response = await fetch(path, { ...options, signal: controller.signal });
      return { response, body: await response.text() };
    } finally {
      clearTimeout(timeout);
    }
  }

  function request(path, type, value, field) {
    const next = requests.then(async () => {
      const nonce = randomBytes(12);
      const sequence = ++seq;
      const body = enc.encode(JSON.stringify(field === undefined ? { seq: sequence, type, value } : { seq: sequence, type, value, field }));
      const sealed = gcm(key, nonce, aad('c2s')).encrypt(body);
      const { response, body: reply } = await fetchBounded(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ n: b64u.enc(nonce), c: b64u.enc(sealed) }),
      });
      return { response, sequence, reply };
    });
    requests = next.catch(() => {});
    return next;
  }

  async function send(type, value, field) {
    const { response: res } = await request('/input', type, value, field);
    if (!res.ok) throw new Error(res.status === 403 ? 'rejected' : 'failed');
  }

  async function run(action) {
    try {
      await action();
      $('status').textContent = 'Sent to the TV.';
    } catch (e) {
      $('status').textContent = e.message === 'rejected'
        ? 'The TV refused this phone. Scan the QR code on the TV again.'
        : 'Could not reach the TV. Check that this phone is on the same network.';
    }
  }

  const sendText = async () => {
    const value = $('text').value;
    if (value.length === 0) return;
    await send('text', value);
    $('text').value = '';
  };

  $('send').addEventListener('click', () => run(sendText));
  $('enter').addEventListener('click', () => run(async () => { await sendText(); await send('key', 'ENTER'); }));
  $('backspace').addEventListener('click', () => run(() => send('key', 'BACKSPACE')));
  const hidden = () => !$('show').checked;
  const sendOnEnter = (e) => { if (e.key === 'Enter') { e.preventDefault(); $('enter').click(); } };
  $('show').addEventListener('change', () => {
    $('text').type = hidden() ? 'password' : 'text';
  });
  $('text').addEventListener('keydown', sendOnEnter);

  let done = false;
  let imageUrl;
  let frameTimer;
  let statusTimer;
  let statusLoading = false;
  let pointer;
  let lastMove = 0;
  let movePending = false;
  let frameVisible = true;
  let frameLoading = false;
  const page = $('page');
  const unseal = (env) => JSON.parse(dec.decode(gcm(key, b64u.dec(env.n), aad('s2c')).decrypt(b64u.dec(env.c))));

  function coordinates(event) {
    const box = page.getBoundingClientRect();
    return { x: Math.max(0, Math.min(1, (event.clientX - box.left) / box.width)), y: Math.max(0, Math.min(1, (event.clientY - box.top) / box.height)) };
  }

  function point(action, event) {
    run(() => send('pointer', JSON.stringify({ action, ...coordinates(event) })));
  }

  page.addEventListener('pointerdown', (event) => {
    if (pointer !== undefined) return;
    event.preventDefault();
    pointer = event.pointerId;
    page.setPointerCapture(pointer);
    lastMove = performance.now();
    point('DOWN', event);
  });
  page.addEventListener('pointermove', (event) => {
    if (pointer !== event.pointerId || movePending || performance.now() - lastMove < 100) return;
    lastMove = performance.now();
    movePending = true;
    run(() => send('pointer', JSON.stringify({ action: 'MOVE', ...coordinates(event) })).finally(() => { movePending = false; }));
  });
  for (const [name, action] of [['pointerup', 'UP'], ['pointercancel', 'CANCEL']]) {
    page.addEventListener(name, (event) => {
      if (pointer !== event.pointerId) return;
      point(action, event);
      page.releasePointerCapture(pointer);
      pointer = undefined;
    });
  }

  async function frames() {
    if (done || document.hidden || !frameVisible || frameLoading) return;
    clearTimeout(frameTimer);
    frameLoading = true;
    try {
      const { response, sequence, reply: payload } = await request('/frame', 'frame', '');
      if (response.ok) {
        const reply = unseal(JSON.parse(payload));
        if (done || document.hidden || !frameVisible) return;
        if (reply.type !== 'frame' || reply.seq !== sequence) throw new Error('rejected');
        const frame = reply.frame;
        const next = URL.createObjectURL(new Blob([b64u.dec(frame.jpeg)], { type: 'image/jpeg' }));
        if (imageUrl) URL.revokeObjectURL(imageUrl);
        imageUrl = next;
        page.src = next;
      }
    } catch { /* Keep the current viewport through a transient connection failure. */ }
    finally { frameLoading = false; }
    if (!done && !document.hidden && frameVisible) frameTimer = setTimeout(frames, 650);
  }
  const previewVisibility = new IntersectionObserver(([entry]) => {
    const changed = frameVisible !== entry.isIntersecting;
    frameVisible = entry.isIntersecting;
    if (!frameVisible) clearTimeout(frameTimer);
    else if (changed && !done && !document.hidden) frames();
  });
  previewVisibility.observe(page.parentElement);

  async function poll() {
    if (done || document.hidden || statusLoading) return;
    clearTimeout(statusTimer);
    statusLoading = true;
    try {
      const { response: res, body } = await fetchBounded('/status', { cache: 'no-store' });
      if (res.ok) {
        const env = JSON.parse(body);
        const status = unseal(env);
        $('step').textContent = status.step || '…';
        if (status.done) {
          done = true;
          clearTimeout(frameTimer);
          if (imageUrl) URL.revokeObjectURL(imageUrl);
          page.removeAttribute('src');
          $('status').textContent = 'Signed in. You can close this page.';
          document.body.classList.add('done');
          return;
        }
      }
    } catch (e) {
      // The TV stops the server after sign-in or timeout; keep polling quietly until then.
    } finally {
      statusLoading = false;
    }
    if (!done && !document.hidden) statusTimer = setTimeout(poll, 1500);
  }
  document.addEventListener('visibilitychange', () => {
    clearTimeout(frameTimer);
    clearTimeout(statusTimer);
    if (!document.hidden && !done) { poll(); frames(); }
  });
  poll();
  frames();
})();
