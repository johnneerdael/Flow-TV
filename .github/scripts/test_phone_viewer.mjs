import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { test } from 'node:test';

const source = readFileSync(new URL('../../app/src/main/assets/account-signin/app.js', import.meta.url), 'utf8');
const flush = () => new Promise(setImmediate);
const envelope = (value) => JSON.stringify({ n: btoa('nonce'), c: Buffer.from(JSON.stringify(value)).toString('base64') });

function viewer() {
  const requests = [];
  const timers = new Map();
  const elements = new Map();
  const events = new Map();
  const created = [];
  const revoked = [];
  let timerId = 0;
  const element = (id) => {
    if (!elements.has(id)) elements.set(id, {
      value: '', checked: false, textContent: '', parentElement: {},
      listeners: new Map(),
      addEventListener(name, fn) { this.listeners.set(name, fn); },
      removeAttribute(name) { delete this[name]; },
      getBoundingClientRect() { return { left: 0, top: 0, width: 360, height: 720 }; },
    });
    return elements.get(id);
  };
  const document = {
    hidden: false, getElementById: element, body: { classList: { add() {} } },
    addEventListener(name, fn) { events.set(name, fn); },
  };
  const context = {
    document, location: { hash: `#${Buffer.alloc(16).toString('base64url')}.${Buffer.alloc(32).toString('base64url')}`, pathname: '/' },
    history: { replaceState() {} }, TextEncoder, TextDecoder, Uint8Array, Blob, AbortController,
    btoa, atob, performance, console,
    window: { nobleCiphers: { randomBytes: (length) => new Uint8Array(length), gcm: () => ({ encrypt: (v) => v, decrypt: (v) => v }) } },
    URL: { createObjectURL() { const value = `blob:${created.length}`; created.push(value); return value; }, revokeObjectURL(value) { revoked.push(value); } },
    IntersectionObserver: class { observe() {} },
    setTimeout(fn, delay) { const id = ++timerId; timers.set(id, { fn, delay }); return id; },
    clearTimeout(id) { timers.delete(id); },
    fetch(path, options) {
      return new Promise((resolve, reject) => {
        const payload = options?.body ? JSON.parse(Buffer.from(JSON.parse(options.body).c, 'base64').toString()) : undefined;
        requests.push({ path, payload, resolve });
        options?.signal?.addEventListener('abort', () => reject(new Error('aborted')));
        if (path === '/input') resolve({ ok: true, status: 204, text: async () => '' });
      });
    },
  };
  vm.runInNewContext(source, context);
  const reply = (request, value) => request.resolve({ ok: true, status: 200, text: async () => envelope(value) });
  const frameReply = (request, seq = request.payload.seq, type = 'frame') => reply(request, { seq, type, frame: { width: 360, height: 720, jpeg: btoa('pixels') } });
  const tick = (delay) => { for (const [id, timer] of [...timers]) if (timer.delay === delay) { timers.delete(id); timer.fn(); } };
  return { requests, timers, document, events, element, created, revoked, reply, frameReply, tick };
}

test('replayed and cross-endpoint replies cannot replace a fresh frame', async () => {
  const v = viewer(); await flush();
  v.reply(v.requests.find((r) => r.path === '/status'), { done: false });
  const first = v.requests.find((r) => r.path === '/frame');
  v.frameReply(first); await flush();
  assert.equal(v.created.length, 1);
  v.tick(650); await flush();
  const second = v.requests.filter((r) => r.path === '/frame')[1];
  v.frameReply(second, first.payload.seq); await flush();
  assert.equal(v.created.length, 1);
  v.tick(650); await flush();
  const third = v.requests.filter((r) => r.path === '/frame')[2];
  v.frameReply(third, third.payload.seq, 'status'); await flush();
  assert.equal(v.created.length, 1);
});

test('visibility changes do not duplicate pending status polling', async () => {
  const v = viewer(); await flush();
  v.document.hidden = true; v.events.get('visibilitychange')();
  v.document.hidden = false; v.events.get('visibilitychange')(); await flush();
  assert.equal(v.requests.filter((r) => r.path === '/status').length, 1);
  v.reply(v.requests.find((r) => r.path === '/status'), { done: false }); await flush();
  assert.equal([...v.timers.values()].filter((t) => t.delay === 1500).length, 1);
});

test('a stalled frame releases queued text after its deadline', async () => {
  const v = viewer(); await flush();
  v.element('text').value = 'fixture text';
  v.element('send').listeners.get('click')(); await flush();
  assert.equal(v.requests.filter((r) => r.path === '/input').length, 0);
  v.tick(5000); await flush();
  assert.equal(v.requests.filter((r) => r.path === '/input').length, 1);
  assert.equal(v.element('text').value, '');
});

test('completion ignores pending frames and releases owned blobs', async () => {
  const v = viewer(); await flush();
  v.reply(v.requests.find((r) => r.path === '/status'), { done: true }); await flush();
  v.frameReply(v.requests.find((r) => r.path === '/frame')); await flush();
  assert.equal(v.created.length, 0);
  assert.equal(v.element('page').src, undefined);
  const w = viewer(); await flush();
  w.frameReply(w.requests.find((r) => r.path === '/frame')); await flush();
  w.reply(w.requests.find((r) => r.path === '/status'), { done: true }); await flush();
  assert.deepEqual(w.revoked, w.created);
  assert.equal(w.element('page').src, undefined);
});
