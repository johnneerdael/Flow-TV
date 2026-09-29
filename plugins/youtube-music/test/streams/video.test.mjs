// video.resolve through the built plugin, on recorded player, watch-page and SponsorBlock answers.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fakeBrowser, fixture, offlinePlugin } from './helpers.mjs';

const entity = (providerId) => ({ kind: 'VIDEO', providerId });
const vod = fixture('player-video-visionos.json');
const live = fixture('player-live-visionos.json');
const cipheredWeb = fixture('player-music-web-remix.json');
const next = fixture('next-chapters.json');
const sponsor = fixture('sponsorblock.json');

test('a video resolves on VISIONOS with formats, captions, chapters, segments and details', async () => {
  const { plugin, playerCalls, requests } = offlinePlugin({ players: { 'VISIONOS@www': vod }, next, sponsor });
  const playback = await plugin.call('video.resolve', { entity: entity('dQw4w9WgXcQ'), maxHeight: 1080, codecs: ['vp9', 'h264'], captionLanguage: 'nl' });
  assert.deepEqual(playerCalls(), ['VISIONOS']);
  assert.ok(requests.some((r) => r.path === '/youtubei/v1/player' && r.host === 'www.youtube.com'), 'videos are asked of the main site');
  assert.equal(playback.kind, 'VOD');
  const videos = playback.formats.filter((f) => f.type === 'VIDEO');
  const audios = playback.formats.filter((f) => f.type === 'AUDIO');
  assert.ok(videos.length > 0 && audios.length > 0);
  assert.ok(videos.every((f) => f.height <= 1080 && /^(vp9|avc1)/.test(f.codecs)));
  assert.equal(videos[0].height, 1080);
  assert.equal(videos[0].codecs, 'vp9');
  assert.ok(videos.every((f) => f.initRange && f.indexRange));
  assert.match(playback.headers['User-Agent'], /Macintosh/);
  assert.ok(playback.expiresInMs > 20_000_000);
  assert.equal(playback.details.title, 'Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)');
  assert.equal(playback.details.channel.kind, 'CHANNEL');
  assert.ok(playback.details.durationSeconds > 200);
  assert.match(playback.details.viewsLabel, /views/);
  const translated = playback.captions.find((c) => c.translated);
  assert.equal(translated.language, 'nl');
  assert.ok(playback.chapters.length > 5);
  assert.deepEqual(playback.skipSegments[0], { startMs: 5683447, endMs: 6212981, category: 'outro' });
});

test('a live stream answers with its manifest and DVR', async () => {
  const { plugin } = offlinePlugin({ players: { VISIONOS: live } });
  const playback = await plugin.call('video.resolve', { entity: entity('rFZHOHl-L8A') });
  assert.equal(playback.kind, 'LIVE');
  assert.match(playback.hlsUrl, /^https:\/\/manifest\.googlevideo\.com\//);
  assert.equal(playback.dvr, true);
  assert.deepEqual(playback.formats, []);
  assert.deepEqual(playback.skipSegments, []);
});

test('an unstarted premiere is UPCOMING with a countdown', async () => {
  const start = Math.floor(Date.now() / 1000) + 3600;
  const offline = {
    playabilityStatus: {
      status: 'LIVE_STREAM_OFFLINE',
      reason: 'Premieres in 60 minutes',
      liveStreamability: { liveStreamabilityRenderer: { offlineSlate: { liveStreamOfflineSlateRenderer: { scheduledStartTime: String(start) } } } },
    },
    videoDetails: { videoId: 'soon', title: 'Soon', author: 'Channel', channelId: 'UC1' },
  };
  const { plugin } = offlinePlugin({ players: { VISIONOS: offline } });
  const playback = await plugin.call('video.resolve', { entity: entity('soon') });
  assert.equal(playback.kind, 'UPCOMING');
  assert.ok(playback.startsInMs > 3_500_000 && playback.startsInMs <= 3_600_000);
  assert.equal(playback.details.title, 'Soon');
});

test('an unplayable video fails as UNAVAILABLE with YouTube reason for the listener', async () => {
  const { plugin, playerCalls } = offlinePlugin({ prepared: true });
  await assert.rejects(plugin.call('video.resolve', { entity: entity('gone') }), (error) => {
    assert.equal(error.code, 'UNAVAILABLE');
    assert.equal(error.userMessage, 'No fixture for this client');
    return true;
  });
  assert.deepEqual(playerCalls(), ['VISIONOS', 'ANDROID_VR', 'ANDROID_VR', 'ANDROID_VR', 'ANDROID_VR', 'ANDROID']);
});

test('a 403 on an unattested URL demotes that client for the next resolves', async () => {
  const { plugin, playerCalls } = offlinePlugin({ players: { VISIONOS: vod } });
  const expire = Math.floor(Date.now() / 1000) + 3600;
  await plugin.call('video.resolve', {
    entity: entity('dQw4w9WgXcQ'),
    failure: { url: `https://rr1.googlevideo.com/videoplayback?expire=${expire}&c=VISIONOS`, status: 403 },
  });
  const calls = playerCalls();
  assert.notEqual(calls[0], 'VISIONOS', 'the demoted client is not asked first');
  assert.equal(calls.at(-1), 'VISIONOS', 'but it is retried when nothing else plays');
});

test('an expired URL does not demote its client', async () => {
  const { plugin, playerCalls } = offlinePlugin({ players: { VISIONOS: vod } });
  await plugin.call('video.resolve', { entity: entity('dQw4w9WgXcQ'), failure: { url: 'https://r.googlevideo.com/videoplayback?expire=1&c=VISIONOS', status: 403 } });
  assert.deepEqual(playerCalls(), ['VISIONOS']);
});

test('the web path mints PO Tokens in the browser and attaches them to the request and the URLs', async () => {
  const browser = fakeBrowser();
  const { plugin, requests, playerCalls } = offlinePlugin({ players: { MWEB: cipheredWeb }, prepared: true, botguard: true, hostOverrides: browser.overrides });
  const playback = await plugin.call('video.resolve', { entity: entity('g6LvR32cdyQ') });
  assert.deepEqual(playerCalls(), ['VISIONOS', 'MWEB']);
  assert.ok(browser.calls.some((call) => call.op === 'open' && call.html === 'assets/po_token.html' && call.baseUrl === 'https://www.youtube.com/'));
  const create = requests.find((r) => r.path === '/api/jnn/v1/Create');
  assert.equal(create.headers['user-agent'], 'FakeWebView/1.0', 'BotGuard is called with the web view own user agent');
  assert.equal(create.body, '["O43z0dpjhgX20SCx4KAo"]');
  const generate = requests.find((r) => r.path === '/api/jnn/v1/GenerateIT');
  assert.equal(generate.body, '["O43z0dpjhgX20SCx4KAo","botguard-response-for-program-1"]');
  const mweb = requests.find((r) => r.json?.context?.client?.clientName === 'MWEB');
  const playerToken = mweb.json.serviceIntegrityDimensions.poToken;
  assert.equal(playerToken.length, 120);
  assert.equal(mweb.headers['x-goog-visitor-id'], 'CgtGaXh0dXJlSWQxMSiAgICAgAY%3D', 'the request carries the visitor the token is bound to');
  assert.equal(mweb.json.playbackContext.contentPlaybackContext.html5Preference, 'HTML5_PREF_WANTS');
  const pots = new Set(playback.formats.map((format) => /[?&]pot=([^&]+)/.exec(format.url)?.[1]));
  assert.equal(pots.size, 1, 'every URL carries the one streaming token');
  const [pot] = pots;
  assert.ok(pot && decodeURIComponent(pot) !== playerToken, 'the streaming token is bound to the visitor, not the video');
  assert.ok(playback.formats.every((format) => /[?&]n=solved_/.test(format.url) && /[?&]sig=/.test(format.url)));
  // The attestation is kept: a second video mints on the same page.
  await plugin.call('video.resolve', { entity: entity('other') }).catch(() => undefined);
  assert.equal(browser.calls.filter((call) => call.op === 'open').length, 1);
});

test('a cold (short) streaming token is retried in fresh pages, three times at most', async () => {
  const browser = fakeBrowser({ tokenLength: 80 });
  const { plugin } = offlinePlugin({ players: { MWEB: cipheredWeb }, prepared: true, botguard: true, hostOverrides: browser.overrides });
  await plugin.call('video.resolve', { entity: entity('g6LvR32cdyQ') });
  assert.equal(browser.calls.filter((call) => call.op === 'open').length, 3);
  assert.equal(browser.calls.filter((call) => call.op === 'close').length, 2, 'superseded pages are closed');
});

test('without BotGuard the web path is skipped and the ladder carries on', async () => {
  const browser = fakeBrowser();
  const { plugin, playerCalls } = offlinePlugin({ players: { MWEB: cipheredWeb }, prepared: true, hostOverrides: browser.overrides });
  await assert.rejects(plugin.call('video.resolve', { entity: entity('g6LvR32cdyQ') }), (error) => /web: PO Token unavailable/.test(error.message));
  assert.ok(!playerCalls().includes('MWEB'));
  assert.ok(browser.calls.some((call) => call.op === 'close'), 'a failed attestation closes its page');
});

const account = JSON.stringify({
  cookie: '__Secure-1PAPISID=fixture-1p; __Secure-3PAPISID=fixture-3p; SID=fixture-sid',
  visitorData: 'CgtGaXh0dXJlSWQxMSiAgICAgAY%3D',
  dataSyncId: 'fixture-user',
  datasyncIdRaw: 'fixture-user||',
});

const withPreRoll = (response, ad) => ({ ...response, adPlacements: [{ adPlacementRenderer: { config: { adPlacementConfig: { kind: 'AD_PLACEMENT_KIND_START' } }, renderer: { instreamVideoAdRenderer: ad } } }] });

test('a video VISIONOS plays needs no signed-in request', async () => {
  const { plugin, playerCalls } = offlinePlugin({ players: { VISIONOS: vod, TVHTML5: cipheredWeb }, prepared: true, secrets: { session: account } });
  const playback = await plugin.call('video.resolve', { entity: entity('xF_QkfZI1mM') });
  assert.deepEqual(playerCalls(), ['VISIONOS']);
  assert.equal(playback.availableInMs, undefined);
});

test('what VISIONOS refuses plays signed in on the downgraded TV client, signed the way YouTube signs its own requests', async () => {
  const { plugin, requests, playerCalls } = offlinePlugin({ players: { TVHTML5: cipheredWeb }, prepared: true, secrets: { session: account } });
  const playback = await plugin.call('video.resolve', { entity: entity('g6LvR32cdyQ') });
  assert.deepEqual(playerCalls(), ['VISIONOS', 'TVHTML5']);
  const tv = requests.find((r) => r.path === '/youtubei/v1/player' && r.json.context.client.clientName === 'TVHTML5');
  assert.equal(tv.json.context.client.clientVersion, '5.20260707');
  assert.match(
    tv.headers.authorization,
    /^SAPISIDHASH (\d+)_[0-9a-f]{40}_u SAPISID1PHASH \1_[0-9a-f]{40}_u SAPISID3PHASH \1_[0-9a-f]{40}_u$/,
    'three hashes salted with the user session; SAPISID falls back to the third-party one',
  );
  assert.equal(tv.headers['x-goog-authuser'], '0');
  assert.equal(tv.headers['x-youtube-bootstrap-logged-in'], 'true');
  assert.equal(tv.headers['x-goog-pageid'], undefined, 'a primary account acts as itself');
  assert.equal(tv.json.context.user?.onBehalfOfUser, undefined);
  assert.equal(tv.json.serviceIntegrityDimensions, undefined, 'the TV client needs no PO Token');
  assert.ok(playback.formats.every((format) => /[?&]n=solved_/.test(format.url)));
  assert.equal(playback.availableInMs, undefined, 'no pre-roll, nothing to wait for');
});

test('a signed-in pre-roll is reported as availableInMs rather than waited out in the call', async () => {
  const sleeps = [];
  const { plugin } = offlinePlugin({
    players: { TVHTML5: withPreRoll(cipheredWeb, { skipOffsetMilliseconds: 5000, playerVars: 'length_seconds=30' }) },
    prepared: true,
    secrets: { session: account },
    hostOverrides: { 'time.sleep': ({ ms }) => (sleeps.push(ms), {}) },
  });
  const playback = await plugin.call('video.resolve', { entity: entity('g6LvR32cdyQ') });
  assert.ok(playback.availableInMs > 4000 && playback.availableInMs <= 5000, `skip point, not ad length: ${playback.availableInMs}`);
  assert.deepEqual(sleeps, []);
});

test('a brand account acts as its page', async () => {
  const brand = JSON.stringify({ cookie: 'SAPISID=fixture-sapisid', dataSyncId: 'fixture-page', datasyncIdRaw: 'fixture-page||fixture-user' });
  const { plugin, requests } = offlinePlugin({ players: { TVHTML5: cipheredWeb }, prepared: true, secrets: { session: brand } });
  await plugin.call('video.resolve', { entity: entity('g6LvR32cdyQ') });
  const tv = requests.find((r) => r.path === '/youtubei/v1/player' && r.json.context.client.clientName === 'TVHTML5');
  assert.equal(tv.headers['x-goog-pageid'], 'fixture-page');
  assert.equal(tv.json.context.user.onBehalfOfUser, 'fixture-page');
});
