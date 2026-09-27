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

  async function send(type, value) {
    const nonce = randomBytes(12);
    const body = enc.encode(JSON.stringify({ seq: ++seq, type, value }));
    const sealed = gcm(key, nonce, aad('c2s')).encrypt(body);
    const res = await fetch('/input', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ n: b64u.enc(nonce), c: b64u.enc(sealed) }),
    });
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
  $('sendEnter').addEventListener('click', () => run(async () => { await sendText(); await send('key', 'ENTER'); }));
  $('enter').addEventListener('click', () => run(() => send('key', 'ENTER')));
  $('tab').addEventListener('click', () => run(() => send('key', 'TAB')));
  $('backspace').addEventListener('click', () => run(() => send('key', 'BACKSPACE')));
  $('show').addEventListener('change', (e) => { $('text').type = e.target.checked ? 'text' : 'password'; });
  $('text').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); $('sendEnter').click(); } });

  let shownActions = '';

  function renderActions(actions) {
    const key = JSON.stringify(actions);
    if (key === shownActions) return;
    shownActions = key;
    const list = $('actions');
    list.replaceChildren(...actions.map((label, index) => {
      const button = document.createElement('button');
      button.textContent = label;
      button.addEventListener('click', () => run(() => send('click', String(index))));
      return button;
    }));
  }

  async function poll() {
    try {
      const res = await fetch('/status', { cache: 'no-store' });
      if (res.ok) {
        const env = await res.json();
        const status = JSON.parse(dec.decode(gcm(key, b64u.dec(env.n), aad('s2c')).decrypt(b64u.dec(env.c))));
        $('step').textContent = status.step || '…';
        renderActions(Array.isArray(status.actions) ? status.actions : []);
        if (status.done) {
          $('status').textContent = 'Signed in. You can close this page.';
          document.body.classList.add('done');
          return;
        }
      }
    } catch (e) {
      // The TV stops the server after sign-in or timeout; keep polling quietly until then.
    }
    setTimeout(poll, 1500);
  }
  poll();
})();
