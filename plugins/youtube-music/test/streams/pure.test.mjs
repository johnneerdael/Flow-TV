// The streams area's pure logic: encodings, query strings, format choice, ladder policy, captions,
// chapters, SponsorBlock, BotGuard parsing and the solver's cache plumbing.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import vm from 'node:vm';
import { fixture, importSource } from './helpers.mjs';

const bytes = await importSource('streams/bytes.ts');
const urls = await importSource('streams/urls.ts');
const formats = await importSource('streams/formats.ts');
const ladder = await importSource('streams/ladder.ts');
const captions = await importSource('streams/captions.ts');
const extras = await importSource('streams/extras.ts');
const botguard = await importSource('streams/potoken/botguard.ts');
const videoFormats = await importSource('streams/video-formats.ts');
const details = await importSource('streams/video-details.ts');
const solver = await importSource('streams/solver.ts');
const audio = await importSource('streams/audio.ts');

/** Encodes xtags the way YouTube does: repeated {key, value} submessages, base64url. */
function xtags(pairs) {
  const out = [];
  for (const [key, value] of Object.entries(pairs)) {
    const k = bytes.utf8Encode(key);
    const v = bytes.utf8Encode(value);
    const inner = [0x0a, k.length, ...k, 0x12, v.length, ...v];
    out.push(0x0a, inner.length, ...inner);
  }
  return bytes.bytesToBase64(out, true);
}

const audioFormat = (itag, mime, bitrate, extra = {}) => ({ itag, mimeType: mime, bitrate, averageBitrate: bitrate, url: `https://x.googlevideo.com/videoplayback?itag=${itag}`, ...extra });
const videoFormat = (itag, codecs, height, bitrate, extra = {}) => ({
  itag,
  mimeType: `video/${codecs.startsWith('avc') || codecs.startsWith('av01') ? 'mp4' : 'webm'}; codecs="${codecs}"`,
  width: Math.round((height * 16) / 9),
  height,
  bitrate,
  url: `https://x.googlevideo.com/videoplayback?itag=${itag}`,
  ...extra,
});

test('base64 and UTF-8 round-trip, url-safe or not', () => {
  const text = 'Beyoncé – 🎵 ok';
  const encoded = bytes.utf8Encode(text);
  assert.equal(bytes.utf8Decode(encoded), text);
  assert.equal(bytes.bytesToBase64(encoded), Buffer.from(text).toString('base64'));
  assert.equal(bytes.bytesToBase64([251, 255], true), '-_8=');
  assert.deepEqual(bytes.base64ToBytes('-_8'), [251, 255]);
  assert.deepEqual(bytes.base64ToBytes('+/8.'), [251, 255]);
  assert.match(bytes.randomCpn(), /^[\w-]{16}$/);
});

test('query strings are read and edited without URL', () => {
  const url = 'https://r.googlevideo.com/videoplayback?expire=1&n=abc&mime=audio%2Fwebm#frag';
  assert.equal(urls.queryParam(url, 'mime'), 'audio/webm');
  assert.equal(urls.withQueryParam(url, 'n', 'x y'), 'https://r.googlevideo.com/videoplayback?expire=1&n=x%20y&mime=audio%2Fwebm#frag');
  assert.equal(urls.withQueryParam('https://a/b', 'pot', 'P'), 'https://a/b?pot=P');
  assert.deepEqual(urls.parseSignatureCipher('s=AB%3D&sp=sig&url=https%3A%2F%2Fa%2Fb%3Fx%3D1'), { s: 'AB=', sp: 'sig', url: 'https://a/b?x=1' });
  assert.equal(urls.parseSignatureCipher('s=AB&url=https%3A%2F%2Fa').sp, 'signature');
  assert.equal(urls.parseSignatureCipher('sp=sig'), undefined);
});

test('xtags decide original, dubbed and DRC tracks', () => {
  assert.deepEqual(formats.decodeXtags(xtags({ acont: 'dubbed', lang: 'de', drc: '1' })), { acont: 'dubbed', lang: 'de', drc: '1' });
  assert.deepEqual(formats.decodeXtags('%%%'), {});
  assert.equal(formats.isOriginal({ mimeType: 'audio/mp4', xtags: xtags({ acont: 'original' }) }), true);
  assert.equal(formats.isOriginal({ mimeType: 'audio/mp4', xtags: xtags({ acont: 'dubbed' }) }), false);
  assert.equal(formats.isOriginal({ mimeType: 'audio/mp4', audioTrack: { id: 'de.3', isAutoDubbed: true } }), false);
  assert.equal(formats.isOriginal({ mimeType: 'audio/mp4', audioTrack: { id: 'en.4' } }), true);
  assert.equal(formats.isOriginal({ mimeType: 'audio/mp4', audioTrack: { id: 'fr.3' } }), false);
  assert.equal(formats.isOriginal({ mimeType: 'audio/mp4', audioTrack: { id: 'fr.3', displayName: 'French (original)' } }), true);
  assert.equal(formats.isDrc({ mimeType: 'audio/mp4', xtags: xtags({ drc: '1' }) }), true);
  assert.equal(formats.isDrc({ mimeType: 'audio/mp4', isDrc: true }), true);
});

test('the music audio format follows quality and language like the app', () => {
  const list = [
    audioFormat(139, 'audio/mp4; codecs="mp4a.40.5"', 48_805),
    audioFormat(140, 'audio/mp4; codecs="mp4a.40.2"', 129_494),
    audioFormat(249, 'audio/webm; codecs="opus"', 50_350),
    audioFormat(251, 'audio/webm; codecs="opus"', 133_929),
    audioFormat(252, 'audio/webm; codecs="opus"', 200_000, { audioTrack: { id: 'de.3', isAutoDubbed: true } }),
    videoFormat(137, 'avc1.640028', 1080, 3_000_000),
  ];
  assert.equal(formats.selectAudioFormat(list, { quality: 'AUTO' }).itag, 251);
  assert.equal(formats.selectAudioFormat(list, { quality: 'HIGH' }).itag, 251);
  assert.equal(formats.selectAudioFormat(list, { quality: 'MEDIUM' }).itag, 140);
  assert.equal(formats.selectAudioFormat(list, { quality: 'LOW' }).itag, 139);
  // An AAC stream beats opus only when it is more than 10 KB/s better.
  const close = [audioFormat(140, 'audio/mp4; codecs="mp4a.40.2"', 140_000), audioFormat(251, 'audio/webm; codecs="opus"', 131_000)];
  assert.equal(formats.selectAudioFormat(close, {}).itag, 251);
  const dubs = [
    audioFormat(251, 'audio/webm; codecs="opus"', 130_000, { audioTrack: { id: 'en.4', displayName: 'English original' } }),
    audioFormat(250, 'audio/webm; codecs="opus"', 70_000, { audioTrack: { id: 'nl.3', displayName: 'Dutch' } }),
  ];
  assert.equal(formats.selectAudioFormat(dubs, { language: 'nl' }).itag, 250);
  assert.equal(formats.selectAudioFormat(dubs, { language: 'original' }).itag, 251);
  assert.equal(formats.selectAudioFormat(dubs, { language: 'ja' }).itag, 251);
  assert.equal(formats.selectAudioFormat([{ ...list[0], url: undefined, signatureCipher: 's=a&url=b' }], { requireDirectUrl: true }), undefined);
});

test('the music video picture: tallest SDR within the cap, codec order, then bitrate', () => {
  const list = [
    videoFormat(137, 'avc1.640028', 1080, 3_000_000),
    videoFormat(248, 'vp9', 1080, 2_000_000),
    videoFormat(399, 'av01.0.08M.08', 1080, 1_500_000),
    videoFormat(337, 'vp9.2', 2160, 9_000_000),
    videoFormat(336, 'vp9.2', 1080, 4_000_000, { colorInfo: { transferCharacteristics: 'COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084' } }),
    videoFormat(136, 'avc1.4d401f', 720, 1_000_000),
    videoFormat(135, 'avc1.4d401e', 720, 1_200_000),
  ];
  assert.equal(formats.selectMusicVideoFormat(list, 2160, ['vp9', 'h264']).itag, 248);
  assert.equal(formats.selectMusicVideoFormat(list, null, ['av1', 'vp9', 'h264']).itag, 399);
  assert.equal(formats.selectMusicVideoFormat(list, 1080, []).itag, 137);
  assert.equal(formats.selectMusicVideoFormat(list, 720, ['h264']).itag, 135);
  assert.equal(formats.selectMusicVideoFormat(list, 480, ['h264']), undefined);
  assert.equal(formats.selectMusicVideoFormat(list, 1080, ['hevc']), undefined);
});

test('formats map to the host MediaFormat with ranges and track info', () => {
  const format = {
    ...audioFormat(251, 'audio/webm; codecs="opus"', 133_929),
    lastModified: '1774299638170205',
    contentLength: '5024388',
    approxDurationMs: '300061',
    initRange: { start: '0', end: '265' },
    indexRange: { start: '266', end: '784' },
    audioTrack: { id: 'en.4', displayName: 'English original', audioIsDefault: true },
    xtags: xtags({ acont: 'original', lang: 'en' }),
  };
  const media = formats.toMediaFormat(format, 'https://u', { 'User-Agent': 'UA' });
  assert.equal(media.id, '251:1774299638170205');
  assert.equal(media.type, 'AUDIO');
  assert.equal(media.mimeType, 'audio/webm');
  assert.equal(media.codecs, 'opus');
  assert.equal(media.contentLength, 5024388);
  assert.deepEqual(media.initRange, { start: 0, end: 265 });
  assert.deepEqual(media.indexRange, { start: 266, end: 784 });
  assert.deepEqual(media.audioTrack, { id: 'en.4', name: 'English original', language: 'en', original: true, drc: false });
  assert.deepEqual(media.headers, { 'User-Agent': 'UA' });
  assert.equal(formats.renditionId({ ...format, isDrc: true }), '251:1774299638170205:drc');
  const hdr = formats.toMediaFormat(videoFormat(336, 'vp9.2', 1080, 1, { colorInfo: { transferCharacteristics: 'COLOR_TRANSFER_CHARACTERISTICS_ARIB_STD_B67' } }), 'u');
  assert.equal(hdr.hdr, true);
  assert.equal(hdr.type, 'VIDEO');
});

test('loudness is relative to the reference, from perceptual loudness first', () => {
  const format = { loudnessDb: 1.5 };
  assert.equal(audio.loudnessDb({ perceptualLoudnessDb: -7.22, loudnessTargetLkfs: -14 }, format), 6.78);
  assert.equal(audio.loudnessDb({ perceptualLoudnessDb: -10 }, format), 4);
  assert.equal(audio.loudnessDb({ loudnessDb: -2 }, format), -2);
  assert.equal(audio.loudnessDb(undefined, format), 1.5);
  assert.equal(audio.loudnessDb(undefined, {}), undefined);
});

test('the music ladders: fast direct clients first, login-only clients never, each once', () => {
  const names = (list) => list.map((client) => `${client.clientName}/${client.clientVersion}`);
  const fast = names(ladder.musicFastLadder());
  assert.equal(fast[0], 'VISIONOS/1.02');
  // ANDROID_VR_NO_AUTH shares 1.61.48's name, version and id, so the app's distinctBy drops it too.
  assert.deepEqual(fast.slice(1, 5), ['ANDROID_VR/1.43.32', 'ANDROID_VR/1.61.48', 'ANDROID/21.03.38', 'ANDROID_CREATOR/25.03.101']);
  assert.equal(new Set(fast).size, fast.length);
  assert.ok(!fast.some((name) => name.startsWith('TVHTML5/') || name.startsWith('WEB_CREATOR/')));
  assert.ok(fast.includes('TVHTML5_SIMPLY_EMBEDDED_PLAYER/2.0') && fast.includes('WEB/2.20260710.06.00'));
  assert.deepEqual(names(ladder.musicRescueLadder()).slice(0, 2), ['TVHTML5_SIMPLY_EMBEDDED_PLAYER/2.0', 'ANDROID_VR/1.43.32']);
  assert.equal(ladder.needsValidation(ladder.MUSIC_MAIN_CLIENT), true);
  assert.equal(ladder.needsValidation(ladder.ANDROID_VR_1_65_10), false);
  assert.equal(ladder.needsValidation(ladder.MUSIC_FAST_CLIENTS[0]), false);
});

test('a failed track skips the fast clients for two minutes', () => {
  let now = 1_000;
  const escalations = new ladder.Escalations(120_000, () => now);
  assert.equal(escalations.isEscalated('a'), false);
  escalations.mark('a');
  now += 119_999;
  assert.equal(escalations.isEscalated('a'), true);
  now += 1;
  assert.equal(escalations.isEscalated('a'), false);
});

test('client gates: demoted for 30 minutes, token refusals on the second strike', () => {
  let now = 0;
  const gates = new ladder.ClientGates(30 * 60_000, () => now);
  gates.reportGated('visionos');
  assert.equal(gates.isGated('VISIONOS'), true);
  assert.deepEqual(gates.ungated(ladder.VIDEO_FAST_CLIENTS), []);
  assert.equal(gates.reportRefused('MWEB'), false);
  assert.equal(gates.isGated('MWEB'), false);
  assert.equal(gates.reportRefused('MWEB'), true);
  assert.equal(gates.isGated('MWEB'), true);
  now += 30 * 60_000;
  assert.equal(gates.isGated('VISIONOS'), false);
  assert.equal(gates.gated(ladder.VIDEO_WEB_CLIENTS).length, 0);
});

test('a refused URL says why: expired, unattested or its token refused', () => {
  const now = 1_700_000_000;
  assert.equal(ladder.classifyDenial(`https://r/v?expire=${now + 10}&c=VISIONOS`, now), 'URL_EXPIRED');
  assert.equal(ladder.classifyDenial(`https://r/v?expire=${now + 3600}&c=VISIONOS`, now), 'ATTESTATION_GATED');
  assert.equal(ladder.classifyDenial(`https://r/v?expire=${now + 3600}&c=MWEB&pot=abc`, now), 'TOKEN_REJECTED');
  assert.equal(ladder.classifyDenial('https://r/v?c=MWEB', now), 'UNKNOWN');
  assert.equal(ladder.clientOfUrl('https://r/v?c=android_vr'), 'ANDROID_VR');
});

test('captions come as WebVTT, with one machine translation when the language is missing', () => {
  const response = fixture('player-video-visionos.json');
  const plain = captions.resolveCaptions(response);
  assert.ok(plain.length > 0);
  for (const track of plain) {
    assert.match(track.url, /fmt=vtt/);
    assert.doesNotMatch(track.url, /fmt=srv3/);
    assert.equal(track.mimeType, 'text/vtt');
  }
  const translated = captions.resolveCaptions(response, 'nl-NL');
  const extra = translated.at(-1);
  assert.equal(translated.length, plain.length + 1);
  assert.equal(extra.language, 'nl');
  assert.equal(extra.translated, true);
  assert.match(extra.url, /&tlang=nl$/);
  assert.equal(captions.resolveCaptions(response, 'en').length, plain.length, 'an existing language is not translated');
  assert.equal(captions.resolveCaptions(response, 'none').length, plain.length);
  assert.equal(captions.timedTextUrl('/api/timedtext?v=1&fmt=srv3&tlang=de&lang=en'), 'https://www.youtube.com/api/timedtext?v=1&lang=en&fmt=vtt');
  const candidates = [
    { tag: 'en', auto: true },
    { tag: 'en-GB', auto: false },
    { tag: 'en', auto: false },
  ];
  assert.deepEqual(captions.bestMatch(candidates, 'en', (c) => c.tag, (c) => c.auto), { tag: 'en', auto: false });
});

test('chapters, watch labels and SponsorBlock segments are read defensively', () => {
  const next = fixture('next-chapters.json');
  const chapters = extras.parseChapters(next);
  assert.ok(chapters.length > 5);
  assert.deepEqual(chapters[0], { title: 'Rodriguez Jr. – Mistral (Stephan Bodzin Remix)', startMs: 0 });
  assert.ok(chapters.every((chapter, i) => i === 0 || chapter.startMs > chapters[i - 1].startMs));
  const labels = extras.parseWatchLabels(next);
  assert.match(labels.viewsLabel, /views/);
  assert.ok(labels.publishedLabel);
  assert.match(labels.channelAvatar.url, /^https:\/\//);
  assert.deepEqual(extras.parseChapters({}), []);
  const segments = extras.parseSkipSegments(JSON.stringify(fixture('sponsorblock.json')));
  assert.deepEqual(segments[0], { startMs: 5683447, endMs: 6212981, category: 'outro' });
  assert.deepEqual(extras.parseSkipSegments('[{"segment":[5,4],"category":"sponsor"},{"segment":[1,2]}]'), []);
  assert.deepEqual(extras.parseSkipSegments('Not Found'), []);
});

test('BotGuard answers are parsed, scrambled or not', () => {
  const data = ['msg', [null, 'interpreter();'], ['https://trusted'], 'hash', 'program', 'globalName', null, 'blob'];
  const plain = botguard.parseChallenge(JSON.stringify([data]));
  assert.equal(plain.program, 'program');
  assert.equal(plain.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue, 'interpreter();');
  assert.equal(plain.interpreterJavascript.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue, 'https://trusted');
  const scrambledBytes = bytes.utf8Encode(JSON.stringify(data)).map((b) => (b - 97 + 256) & 255);
  const scrambled = botguard.parseChallenge(JSON.stringify([null, bytes.bytesToBase64(scrambledBytes)]));
  assert.deepEqual(scrambled, plain);
  const integrity = botguard.parseIntegrityToken(JSON.stringify([bytes.bytesToBase64([1, 2, 3], true), 43200]));
  assert.deepEqual(integrity, { bytes: [1, 2, 3], lifetimeSeconds: 43200 });
  assert.throws(() => botguard.parseIntegrityToken('[""]'));
  assert.throws(() => botguard.parseIntegrityToken('["abc", 0]'));
  assert.equal(botguard.poTokenFromBytes([251, 255]), '-_8=');
});

test('streaming tokens bind to the 11-character visitor id; short tokens force a new attestation', () => {
  assert.equal(botguard.visitorIdOf('CgtGaXh0dXJlSWQxMSiAgICAgAY%3D'), 'FixtureId11');
  assert.equal(botguard.gvsBinding('not-base64-%%'), 'not-base64-%%');
  assert.equal(botguard.visitorIdOf(undefined), undefined);
  assert.equal(botguard.isLowTrust('a'.repeat(109)), true);
  assert.equal(botguard.isLowTrust(`${'a'.repeat(110)}==`), false);
  const base = { forced: false, hasSession: true, expired: false, visitorChanged: false, lowTrust: false };
  assert.equal(botguard.shouldReattest(base), false);
  for (const key of ['forced', 'expired', 'visitorChanged', 'lowTrust']) assert.equal(botguard.shouldReattest({ ...base, [key]: true }), true, key);
  assert.equal(botguard.shouldReattest({ ...base, hasSession: false }), true);
});

test('video formats offered: within height and codecs, languages in order', () => {
  const resolved = [
    videoFormat(137, 'avc1.640028', 1080, 3_000_000),
    videoFormat(248, 'vp9', 1080, 2_000_000),
    videoFormat(313, 'vp9', 2160, 9_000_000),
    videoFormat(136, 'avc1.4d401f', 720, 1_000_000),
  ].map((format) => ({ format, url: format.url }));
  const itags = (list) => list.map(({ format }) => format.itag);
  assert.deepEqual(itags(videoFormats.filterVideoFormats(resolved, 1080, ['vp9', 'h264'])), [248, 137, 136]);
  assert.deepEqual(itags(videoFormats.filterVideoFormats(resolved, null, [])), [313, 137, 248, 136]);
  assert.deepEqual(itags(videoFormats.filterVideoFormats(resolved, 360, ['av1'])), [313, 137, 248, 136], 'filters that empty the list are not applied');
  const tracks = [
    audioFormat(1, 'audio/mp4', 100, { audioTrack: { id: 'de.3', displayName: 'German' } }),
    audioFormat(2, 'audio/mp4', 100, { audioTrack: { id: 'en.4', displayName: 'English original' }, isDrc: true }),
    audioFormat(3, 'audio/mp4', 100, { audioTrack: { id: 'en.4', displayName: 'English original' } }),
    audioFormat(4, 'audio/mp4', 50, { audioTrack: { id: 'nl.3', displayName: 'Dutch' } }),
  ].map((format) => ({ format, url: format.url }));
  assert.deepEqual(itags(videoFormats.orderAudioFormats(tracks, 'nl')), [4, 3, 2, 1]);
  assert.deepEqual(itags(videoFormats.orderAudioFormats(tracks, null)), [3, 2, 1, 4]);
});

test('an unstarted premiere is a countdown, not an error', () => {
  const entity = { kind: 'VIDEO', providerId: 'x' };
  const offline = {
    playabilityStatus: {
      status: 'LIVE_STREAM_OFFLINE',
      reason: 'Premieres in 2 hours',
      liveStreamability: { liveStreamabilityRenderer: { offlineSlate: { liveStreamOfflineSlateRenderer: { scheduledStartTime: '2000' } } } },
    },
    videoDetails: { title: 'Soon', author: 'Channel', channelId: 'UC1', lengthSeconds: '0' },
  };
  assert.equal(details.looksUpcoming(offline), true);
  assert.equal(details.looksUpcoming({ ...offline, streamingData: { hlsManifestUrl: 'x' } }), false);
  assert.equal(details.looksUpcoming({ playabilityStatus: { status: 'ERROR', reason: 'Video unavailable' } }), false);
  assert.equal(details.startsInMs(offline, 1_000_000), 1_000_000);
  assert.equal(details.startsInMs(offline, 3_000_000), undefined);
  const upcoming = details.upcomingPlayback(entity, [{ playabilityStatus: { status: 'ERROR' } }, offline], {}, 1_500_000);
  assert.equal(upcoming.kind, 'UPCOMING');
  assert.equal(upcoming.startsInMs, 500_000);
  assert.equal(upcoming.details.title, 'Soon');
  assert.equal(upcoming.details.durationSeconds, undefined);
});

test('the solver cache: player id, signature timestamp and the prepared script', () => {
  assert.equal(solver.parsePlayerId(`a.src='https:\\/\\/www.youtube.com\\/s\\/player\\/fb50cd46\\/www-widgetapi.vflset\\/www-widgetapi.js'`), 'fb50cd46');
  assert.equal(solver.parsePlayerId('nothing'), undefined);
  assert.equal(solver.parseSignatureTimestamp('x={signatureTimestamp:20719,foo:1}'), 20719);
  assert.equal(solver.parseSignatureTimestamp('sts:20719'), 20719);
  assert.equal(solver.preparedKey('fb50cd46'), 'player:fb50cd46:0.8.0');
  assert.equal(solver.playerJsUrl('fb50cd46'), 'https://www.youtube.com/s/player/fb50cd46/player_ias.vflset/en_US/base.js');
  const context = vm.createContext({});
  vm.runInContext(solver.preparedSource('abc', '_result.n = (n) => n + "!"; _result.sig = (s) => s.toUpperCase(); var leaked = 1;'), context);
  assert.equal(context.__solvers.player, 'abc');
  assert.equal(context.__solvers.n('x'), 'x!');
  assert.equal(context.__solvers.sig('ab'), 'AB');
  assert.equal(context.leaked, undefined, 'the player script runs in its own scope');
});

test('adWaitMs: the skip point when skippable, else the ad length, over placements and slots', async () => {
  const { adWaitMs } = await importSource('streams/player.ts');
  const start = (ad) => ({ adPlacementRenderer: { config: { adPlacementConfig: { kind: 'AD_PLACEMENT_KIND_START' } }, renderer: { instreamVideoAdRenderer: ad } } });
  assert.equal(adWaitMs({}), 0);
  assert.equal(adWaitMs({ adPlacements: [start({ skipOffsetMilliseconds: 5000, playerVars: 'length_seconds=30' })] }), 5000);
  assert.equal(adWaitMs({ adPlacements: [start({ playerVars: 'video_id=x&length_seconds=15' })] }), 15000);
  const midRoll = { adPlacementRenderer: { config: { adPlacementConfig: { kind: 'AD_PLACEMENT_KIND_MILLISECONDS' } }, renderer: { instreamVideoAdRenderer: { playerVars: 'length_seconds=20' } } } };
  assert.equal(adWaitMs({ adPlacements: [midRoll] }), 0, 'only pre-rolls hold the video back');
  const slot = {
    adSlotRenderer: {
      adSlotMetadata: { triggerEvent: 'SLOT_TRIGGER_EVENT_BEFORE_CONTENT' },
      fulfillmentContent: { fulfilledLayout: { playerBytesAdLayoutRenderer: { renderingContent: { playerBytesSequentialLayoutRenderer: { sequentialLayouts: [
        { playerBytesAdLayoutRenderer: { renderingContent: { instreamVideoAdRenderer: { skipOffsetMilliseconds: 5000 } } } },
        { playerBytesAdLayoutRenderer: { renderingContent: { instreamVideoAdRenderer: { playerVars: 'length_seconds=6' } } } },
      ] } } } } },
    },
  };
  assert.equal(adWaitMs({ adSlots: [slot] }), 11000, 'a sequence of ads adds up');
});
