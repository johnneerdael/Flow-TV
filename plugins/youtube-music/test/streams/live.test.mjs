// Against YouTube itself, only with LIVE=1: warmUp's solvers must answer exactly what yt-dlp's EJS
// answers on the same player, and every URL the plugin returns must serve its first 64 KB.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import vm from 'node:vm';
import { loadPlugin } from '@milkbeat/mbplugin/harness';
import { pluginDir } from './helpers.mjs';

const live = process.env.LIVE === '1';
const options = { skip: !live && 'set LIVE=1 to run against YouTube', timeout: 120_000 };

const N_CHALLENGES = ['ZdZIqFPQK-Ty8wId', 'dmAa3SiX2TeDqpxK'];
const SIG_CHALLENGE = 'AOq0QJ8wRQIgWJbUgnE4Wt04xXsC5ceshDuUzqsI4ahBoIOHrKs2RzICIQCy7CKn8u9HqzNqtFWP7GHHsfUmr4Ut3eVZEqWbGt7rJw==AOq0QJ8wRQ';

const codeCache = new Map();
const logs = [];
const plugin = live ? loadPlugin(pluginDir, { codeCache, log: (level, message) => logs.push(`${level} ${message}`) }) : undefined;
const track = (videoId, extra = {}) => ({ track: { ref: { kind: 'TRACK', providerId: videoId }, title: videoId, ids: { ytm: videoId } }, ...extra });

async function firstBytes(url, headers = {}) {
  const response = await fetch(url, { headers: { ...headers, Range: 'bytes=0-65535' } });
  const body = await response.arrayBuffer();
  return { status: response.status, length: body.byteLength };
}

async function assertServes(t, label, url, headers) {
  const { status, length } = await firstBytes(url, headers);
  t.diagnostic(`${label}: HTTP ${status}, ${length} bytes`);
  assert.equal(status, 206, `${label} answered ${status}`);
  assert.equal(length, 65536);
}

test('warmUp prepares the current player in Node, and its solvers match yt-dlp EJS', options, async (t) => {
  const started = Date.now();
  await plugin.call('lifecycle.warmUp');
  const elapsed = Date.now() - started;
  const state = JSON.parse(plugin.storage.get('streams.player'));
  t.diagnostic(`warmUp ${elapsed} ms for player ${state.id}, signature timestamp ${state.sts}`);
  assert.ok(state.sts > 20000);
  const prepared = codeCache.get(`player:${state.id}:0.8.0`);
  assert.ok(prepared, 'the prepared solvers are in the code cache');

  // Reference: the EJS solver run directly on the same player, as yt-dlp does.
  const player = await (await fetch(`https://www.youtube.com/s/player/${state.id}/player_ias.vflset/en_US/base.js`)).text();
  const reference = vm.createContext({});
  vm.runInContext(readFileSync(join(pluginDir, 'assets/solver/yt.solver.lib.js'), 'utf8'), reference);
  vm.runInContext('var meriyah = lib.meriyah, astring = lib.astring;', reference);
  vm.runInContext(readFileSync(join(pluginDir, 'assets/solver/yt.solver.core.js'), 'utf8'), reference);
  reference.input = { type: 'player', player, requests: [{ type: 'n', challenges: N_CHALLENGES }, { type: 'sig', challenges: [SIG_CHALLENGE] }] };
  const expected = vm.runInContext('jsc(input)', reference).responses;

  // The plugin's cached script, loaded the way a later start loads it: in a fresh context.
  const cached = vm.createContext({});
  vm.runInContext(prepared, cached);
  const n = Object.fromEntries(N_CHALLENGES.map((challenge) => [challenge, cached.__solvers.n(challenge)]));
  const sig = { [SIG_CHALLENGE]: cached.__solvers.sig(SIG_CHALLENGE) };
  assert.deepEqual([{ type: 'result', data: n }, { type: 'result', data: sig }], JSON.parse(JSON.stringify(expected)));
  for (const challenge of N_CHALLENGES) assert.notEqual(n[challenge], challenge);
  t.diagnostic(`n ${JSON.stringify(n)}`);
});

test('a music track resolves and its URL serves', options, async (t) => {
  const started = Date.now();
  const stream = await plugin.call('audio.resolve', track('g6LvR32cdyQ'));
  t.diagnostic(`resolved in ${Date.now() - started} ms: ${stream.renditionId} ${stream.mimeType} loudness ${stream.loudnessDb}`);
  await assertServes(t, 'audio', stream.url, stream.headers);
});

test('a music video resolves its picture from the same response, and both serve', options, async (t) => {
  const stream = await plugin.call('audio.resolve', track('g6LvR32cdyQ', { video: true, maxVideoHeight: 1080, videoCodecs: ['vp9', 'h264'] }));
  assert.ok(stream.video, 'a music video has a picture');
  t.diagnostic(`video ${stream.video.id} ${stream.video.qualityLabel} ${stream.video.codecs}`);
  await assertServes(t, 'audio', stream.url, stream.headers);
  await assertServes(t, 'video', stream.video.url, stream.video.headers);
});

test('after a failed stream the track resolves through the deciphered WEB_REMIX path, and serves', options, async (t) => {
  const failure = { url: 'https://rr1---sn.googlevideo.com/videoplayback?c=VISIONOS', status: 403 };
  const stream = await plugin.call('audio.resolve', track('g6LvR32cdyQ', { failure }));
  assert.match(stream.headers['User-Agent'], /Firefox/, 'resolved by the web client');
  assert.match(stream.url, /[?&]c=WEB_REMIX/);
  await assertServes(t, 'deciphered audio', stream.url, stream.headers);
});

test('a regular video resolves with chapters and its formats serve', options, async (t) => {
  const started = Date.now();
  const playback = await plugin.call('video.resolve', { entity: { kind: 'VIDEO', providerId: 'xF_QkfZI1mM' }, maxHeight: 1080, codecs: ['vp9', 'h264'] });
  t.diagnostic(`resolved in ${Date.now() - started} ms: ${playback.formats.length} formats, ${playback.chapters.length} chapters, ${playback.skipSegments.length} segments`);
  assert.equal(playback.kind, 'VOD');
  assert.match(playback.details.title, /Bodzin/);
  assert.ok(playback.chapters.length > 0);
  const video = playback.formats.find((format) => format.type === 'VIDEO');
  const audio = playback.formats.find((format) => format.type === 'AUDIO');
  await assertServes(t, `video ${video.qualityLabel}`, video.url, { ...playback.headers, ...video.headers });
  await assertServes(t, 'audio', audio.url, { ...playback.headers, ...audio.headers });
});

test('a live stream resolves to a manifest whose first segment serves', options, async (t) => {
  const playback = await plugin.call('video.resolve', { entity: { kind: 'VIDEO', providerId: 'rFZHOHl-L8A' } });
  assert.equal(playback.kind, 'LIVE');
  const url = playback.hlsUrl ?? playback.dashUrl;
  assert.ok(url);
  const master = await (await fetch(url, { headers: playback.headers })).text();
  assert.match(master, /^#EXTM3U/);
  const variant = master.split('\n').find((line) => line.startsWith('https://'));
  const media = await (await fetch(variant, { headers: playback.headers })).text();
  const segment = media.split('\n').find((line) => line.startsWith('https://'));
  const { status, length } = await firstBytes(segment, playback.headers);
  t.diagnostic(`live segment: HTTP ${status}, ${length} bytes, dvr ${playback.dvr}`);
  assert.ok(status === 206 || status === 200, `segment answered ${status}`);
  assert.ok(length > 0);
});
