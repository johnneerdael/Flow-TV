// lifecycle.warmUp: it runs in a context of its own, so everything it prepares must reach later calls
// through the host (the code cache, storage and the browser), which these tests check by handing that
// state to a second plugin instance.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadPlugin } from '@milkbeat/mbplugin/harness';
import { FAKE_SOLVERS, fakeBrowser, fixture, offlinePlugin, PLAYER_ID, pluginDir, STS } from './helpers.mjs';

test('warmUp finds the player and its signature timestamp, and keeps cached solvers', async () => {
  const warm = offlinePlugin();
  warm.codeCache.set(`player:${PLAYER_ID}:0.8.0`, FAKE_SOLVERS);
  await warm.plugin.call('lifecycle.warmUp');
  const state = JSON.parse(warm.plugin.storage.get('streams.player'));
  assert.equal(state.id, PLAYER_ID);
  assert.equal(state.sts, STS);
  assert.ok(!warm.codeCache.has('ejs:0.8.0'), 'no preprocessing when the prepared solvers are cached');

  // The playback context: same code cache and storage, none of warmUp's memory.
  const requests = [];
  const players = { WEB_REMIX: fixture('player-music-web-remix.json') };
  const main = loadPlugin(pluginDir, {
    storage: Object.fromEntries(warm.plugin.storage),
    codeCache: warm.codeCache,
    http: async (request) => {
      const url = new URL(request.url);
      requests.push(url.pathname);
      if (url.pathname === '/sw.js_data') return { status: 200, url: request.url, headers: {}, body: `)]}'\n[["x",null,["CgtGaXh0dXJlSWQxMSiAgICAgAY%3D"]]]` };
      const client = request.body && JSON.parse(request.body).context.client.clientName;
      return { status: 200, url: request.url, headers: {}, body: JSON.stringify(players[client] ?? { playabilityStatus: { status: 'UNPLAYABLE' } }) };
    },
  });
  const failure = { url: 'https://r.googlevideo.com/videoplayback?c=VISIONOS', status: 403 };
  const stream = await main.call('audio.resolve', { track: { ref: { kind: 'TRACK', providerId: 'x' }, title: 't', ids: { ytm: 'g6LvR32cdyQ' } }, failure });
  assert.match(stream.url, /n=solved_/);
  assert.ok(!requests.includes('/iframe_api') && !requests.some((path) => path.endsWith('base.js')), 'the playback context reuses what warmUp stored');
});

test('warmUp survives a player script the solver cannot read', async () => {
  const warm = offlinePlugin({ playerJs: 'var broken = true; var x = {signatureTimestamp:20719};' });
  await warm.plugin.call('lifecycle.warmUp');
  assert.ok(warm.logs.some((line) => line.startsWith('WARN Solver warm-up failed')));
  assert.equal(JSON.parse(warm.plugin.storage.get('streams.player')).sts, STS);
});

test('warmUp attests BotGuard once, and the playback context mints on that page', async () => {
  const browser = fakeBrowser();
  const warm = offlinePlugin({ prepared: true, botguard: true, hostOverrides: browser.overrides });
  await warm.plugin.call('lifecycle.warmUp');
  assert.equal(browser.calls.filter((call) => call.op === 'open').length, 1);
  const stored = JSON.parse(warm.plugin.storage.get('streams.botguard'));
  assert.equal(stored.session, 'session-1');
  assert.equal(stored.lowTrust, false);

  const requests = [];
  const main = loadPlugin(pluginDir, {
    storage: Object.fromEntries(warm.plugin.storage),
    codeCache: warm.codeCache,
    hostOverrides: browser.overrides,
    http: async (request) => {
      const url = new URL(request.url);
      requests.push(url.pathname);
      if (url.pathname === '/sw.js_data') return { status: 200, url: request.url, headers: {}, body: `)]}'\n[["x",null,["CgtGaXh0dXJlSWQxMSiAgICAgAY%3D"]]]` };
      const client = request.body && JSON.parse(request.body).context.client.clientName;
      const answer = client === 'MWEB' ? fixture('player-music-web-remix.json') : { playabilityStatus: { status: 'UNPLAYABLE' } };
      return { status: 200, url: request.url, headers: {}, body: JSON.stringify(answer) };
    },
  });
  const playback = await main.call('video.resolve', { entity: { kind: 'VIDEO', providerId: 'g6LvR32cdyQ' } });
  assert.ok(playback.formats.every((format) => format.url.includes(`pot=${encodeURIComponent(stored.streaming)}`)));
  assert.equal(browser.calls.filter((call) => call.op === 'open').length, 1, 'no second attestation');
  assert.ok(!requests.includes('/api/jnn/v1/Create'));
});
