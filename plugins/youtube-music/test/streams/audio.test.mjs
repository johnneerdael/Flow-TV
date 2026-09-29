// audio.resolve and audio.reportPlayback through the built plugin, on recorded player responses.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fixture, offlinePlugin } from './helpers.mjs';

const VISIONOS_UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15';
const track = (extra = {}) => ({
  track: { ref: { kind: 'TRACK', providerId: 'g6LvR32cdyQ' }, title: 'Track', ids: { ytm: 'g6LvR32cdyQ' } },
  ...extra,
});
const visionos = fixture('player-music-visionos.json');
const webRemix = fixture('player-music-web-remix.json');

test('a track resolves on VISIONOS: best audio, loudness, relative expiry, no solver needed', async () => {
  const { plugin, playerCalls, requests } = offlinePlugin({ players: { VISIONOS: visionos } });
  const stream = await plugin.call('audio.resolve', track());
  assert.deepEqual(playerCalls(), ['VISIONOS']);
  assert.ok(!requests.some((r) => r.path.endsWith('/base.js')), 'the fast path never fetches the player script');
  assert.equal(stream.cacheKey, 'g6LvR32cdyQ');
  assert.match(stream.renditionId, /^251:\d+$/);
  assert.equal(stream.mimeType, 'audio/webm');
  assert.equal(stream.codecs, 'opus');
  assert.ok(stream.contentLength > 0);
  assert.match(stream.url, /^https:\/\/rr1---sn-fixture\.googlevideo\.com\/videoplayback\?.*itag=251/);
  assert.deepEqual(stream.headers, { 'User-Agent': VISIONOS_UA });
  assert.equal(stream.loudnessDb, 6.78);
  assert.ok(stream.expiresInMs > 21_000_000 && stream.expiresInMs <= 21_540_000);
  assert.deepEqual(JSON.parse(stream.trackingToken), { v: 'g6LvR32cdyQ' });
  assert.equal(stream.video, undefined);
});

test('quality settings pick the lowest and the ~128 kb/s stream', async () => {
  const { plugin } = offlinePlugin({ players: { VISIONOS: visionos } });
  assert.match((await plugin.call('audio.resolve', track({ quality: 'LOW' }))).renditionId, /^139:/);
  assert.match((await plugin.call('audio.resolve', track({ quality: 'MEDIUM' }))).renditionId, /^140:/);
});

test('the music video comes from the same response, in the TV codec order and height cap', async () => {
  const { plugin, playerCalls } = offlinePlugin({ players: { VISIONOS: visionos } });
  const vp9 = await plugin.call('audio.resolve', track({ video: true, maxVideoHeight: 2160, videoCodecs: ['vp9', 'h264'] }));
  assert.equal(vp9.video.type, 'VIDEO');
  assert.equal(vp9.video.height, 1080);
  assert.equal(vp9.video.codecs, 'vp9');
  assert.deepEqual(vp9.video.headers, { 'User-Agent': VISIONOS_UA });
  assert.ok(vp9.video.initRange && vp9.video.indexRange);
  const h264 = await plugin.call('audio.resolve', track({ video: true, maxVideoHeight: 720, videoCodecs: ['h264'] }));
  assert.equal(h264.video.height, 720);
  assert.match(h264.video.codecs, /^avc1/);
  assert.deepEqual(playerCalls(), ['VISIONOS'], 'the second resolve is served from the cached response');
});

test('two resolves of one track share one lookup', async () => {
  const { plugin, playerCalls } = offlinePlugin({ players: { VISIONOS: visionos } });
  await Promise.all([plugin.call('audio.resolve', track()), plugin.call('audio.resolve', track())]);
  assert.deepEqual(playerCalls(), ['VISIONOS']);
});

test('a failed stream escalates past the fast clients to WEB_REMIX, deciphered with the cached solvers', async () => {
  const { plugin, playerCalls, requests } = offlinePlugin({ players: { VISIONOS: visionos, WEB_REMIX: webRemix }, prepared: true });
  await plugin.call('audio.resolve', track());
  const stream = await plugin.call('audio.resolve', track({ failure: { url: 'https://r.googlevideo.com/videoplayback?c=VISIONOS', status: 403 } }));
  assert.deepEqual(playerCalls(), ['VISIONOS', 'WEB_REMIX']);
  const main = requests.find((r) => r.json?.context?.client?.clientName === 'WEB_REMIX');
  assert.equal(main.json.playbackContext.contentPlaybackContext.signatureTimestamp, 20719);
  assert.equal(main.json.context.client.hl, 'en');
  assert.equal(main.json.context.client.gl, 'US');
  assert.match(stream.url, /[?&]sig=zyx152egnellahCgiS(&|$)/, 'the signature is solved into its sp parameter');
  assert.match(stream.url, /[?&]n=solved_nChallengeAbc123(&|$)/, 'the n challenge is transformed');
  assert.equal(stream.headers['User-Agent'], 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0');
  const again = await plugin.call('audio.resolve', track());
  assert.equal(again.url, stream.url, 'within two minutes the track stays escalated and cached');
});

test('nothing playable fails as UNAVAILABLE after the whole ladder', async () => {
  const { plugin, playerCalls } = offlinePlugin({ prepared: true });
  await assert.rejects(plugin.call('audio.resolve', track()), (error) => error.code === 'UNAVAILABLE');
  const calls = playerCalls();
  assert.equal(calls[0], 'VISIONOS');
  assert.ok(calls.includes('WEB_REMIX') && calls.includes('ANDROID'), calls.join());
  assert.ok(!calls.includes('TVHTML5') && !calls.includes('WEB_CREATOR'), 'login-only clients are skipped');
});

test('a track without a YouTube id is NOT_FOUND', async () => {
  const { plugin } = offlinePlugin();
  const request = { track: { ref: { kind: 'TRACK', providerId: 'spotify:1' }, title: 'Elsewhere', ids: { isrc: 'X' } } };
  await assert.rejects(plugin.call('audio.resolve', request), (error) => error.code === 'NOT_FOUND');
});

test('reportPlayback does nothing signed out', async () => {
  const { plugin, requests } = offlinePlugin();
  await plugin.call('audio.reportPlayback', { entity: { kind: 'TRACK', providerId: 'x' }, trackingToken: '{"v":"x"}', playedMs: 60_000 });
  assert.equal(requests.length, 0);
});

test('signed in, the account request records the listen through its tracking URL', async () => {
  const session = { cookie: 'SAPISID=abc123; SID=s', visitorData: 'CgtGaXh0dXJlSWQxMSiAgICAgAY%3D', dataSyncId: 'sync' };
  const { plugin, requests } = offlinePlugin({
    players: { 'VISIONOS@music': visionos, 'WEB_REMIX@music': webRemix },
    prepared: true,
    secrets: { session: JSON.stringify(session) },
  });
  const stream = await plugin.call('audio.resolve', track());
  const signedIn = requests.find((r) => r.json?.context?.client?.clientName === 'WEB_REMIX');
  assert.ok(signedIn, 'the account player request runs beside the stream lookup');
  assert.match(signedIn.headers.authorization, /^SAPISIDHASH \d+_[0-9a-f]{40}_u$/, 'salted with the user session from DATASYNC_ID');
  assert.equal(signedIn.json.context.user?.onBehalfOfUser, undefined, 'a primary account acts as itself');
  await plugin.call('audio.reportPlayback', { entity: { kind: 'TRACK', providerId: 'g6LvR32cdyQ' }, trackingToken: stream.trackingToken, playedMs: 60_000 });
  const ping = requests.find((r) => r.path === '/api/stats/playback');
  assert.equal(ping.host, 'music.youtube.com');
  assert.match(ping.url, /[?&]ver=2&c=WEB_REMIX&cpn=[\w-]{16}$/);
  assert.equal(ping.headers.cookie, session.cookie);
  assert.match(ping.headers.authorization, /^SAPISIDHASH /);
});
