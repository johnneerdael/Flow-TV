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

  async function send(type, value, field) {
    const nonce = randomBytes(12);
    const body = enc.encode(JSON.stringify(field === undefined ? { seq: ++seq, type, value } : { seq: ++seq, type, value, field }));
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

  const fieldInputs = () => Array.from($('fields').querySelectorAll('input'));

  // A page with several fields at once (a username and a password) gets one box each, filled in order;
  // otherwise the one box types into whatever field the TV page has focused.
  const sendText = async () => {
    const boxes = fieldInputs();
    if (boxes.length > 0) {
      for (const box of boxes) {
        if (box.value.length === 0) continue;
        await send('text', box.value, Number(box.dataset.field));
        box.value = '';
      }
      return;
    }
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
  const hidden = () => !$('show').checked;
  const sendOnEnter = (e) => { if (e.key === 'Enter') { e.preventDefault(); $('sendEnter').click(); } };
  $('show').addEventListener('change', () => {
    $('text').type = hidden() ? 'password' : 'text';
    for (const box of fieldInputs()) if (box.dataset.secret) box.type = hidden() ? 'password' : 'text';
  });
  $('text').addEventListener('keydown', sendOnEnter);

  let shownFields = '';

  function renderFields(fields) {
    const key = JSON.stringify(fields);
    if (key === shownFields) return;
    shownFields = key;
    $('fields').replaceChildren(...fields.map((field, index) => {
      const box = document.createElement('input');
      const label = field.label || `Field ${index + 1}`;
      box.type = field.secret && hidden() ? 'password' : 'text';
      box.placeholder = label;
      box.setAttribute('aria-label', label);
      box.dataset.field = String(index);
      if (field.secret) box.dataset.secret = '1';
      for (const [name, value] of [['autocomplete', 'off'], ['autocapitalize', 'off'], ['autocorrect', 'off'], ['spellcheck', 'false']]) box.setAttribute(name, value);
      box.addEventListener('keydown', sendOnEnter);
      return box;
    }));
    document.body.classList.toggle('has-fields', fields.length > 0);
  }

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
        renderFields(Array.isArray(status.fields) ? status.fields : []);
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
