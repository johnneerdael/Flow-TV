// Audio: a track resolves to Beatport's full-length HLS stream, a preview clip is refused as needing a
// subscription, and a play is reported as Beatport's apps report it.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { offlinePlugin, pathIs } from './helpers.mjs';

const TRACK_ID = '19599656';
const LENGTH_MS = 334_860;
const expiry = () => Math.floor(Date.now() / 1000) + 3600;
const streamUrl = (epoch) => `https://needledrop.beatport.com/${epoch}_0123abcd/7/3/8/fixture.128k.aac.m3u8?start=0&end=${LENGTH_MS}`;
const track = (overrides = {}) => ({
  ref: { kind: 'TRACK', providerId: TRACK_ID },
  title: 'Fixture',
  durationMs: LENGTH_MS,
  ids: { beatport: TRACK_ID },
  ...overrides,
});
const streaming = (body) => [[pathIs(`/v4/catalog/tracks/${TRACK_ID}/stream/`), { body }]];

describe('audio', () => {
  test('a track resolves to its full-length HLS stream, asked for its whole length', async () => {
    const epoch = expiry();
    const { call, calls } = offlinePlugin({ routes: streaming({ stream_url: streamUrl(epoch), sample_start_ms: 0, sample_end_ms: LENGTH_MS }) });
    const stream = await call('audio.resolve', { track: track() });
    assert.equal(stream.url, streamUrl(epoch));
    assert.deepEqual([stream.mimeType, stream.codecs, stream.bitrate, stream.renditionId], ['application/x-mpegURL', 'mp4a.40.2', 128_000, '128k.aac.m3u8']);
    assert.equal(stream.cacheKey, `beatport:${TRACK_ID}:128k.aac.m3u8`);
    assert.ok(stream.expiresInMs > 3_590_000 && stream.expiresInMs <= 3_600_000);
    assert.deepEqual(calls[0].query, { start: '0', end: String(LENGTH_MS) });
    assert.equal(calls[0].authorization, 'Bearer fixture-token');
  });

  test('a preview clip is refused as needing a subscription', async () => {
    const { call } = offlinePlugin({ routes: streaming({ stream_url: streamUrl(expiry()), sample_start_ms: 60_000, sample_end_ms: 180_000 }) });
    await assert.rejects(call('audio.resolve', { track: track() }), { code: 'UNAVAILABLE', userMessage: /subscription/ });
  });

  test('a track that is not Beatport’s is not found', async () => {
    const { call, calls } = offlinePlugin();
    await assert.rejects(call('audio.resolve', { track: track({ ref: { kind: 'TRACK', providerId: 'dQw4w9WgXcQ' }, ids: {} }) }), { code: 'NOT_FOUND' });
    assert.equal(calls.length, 0);
  });

  test('a play is reported with the install’s device id, which stays the same', async () => {
    const { call, calls } = offlinePlugin({ routes: [[pathIs('/v4/events/play/'), { body: { processed_events: 1 } }]] });
    const before = Math.floor(Date.now() / 1000);
    await call('audio.reportPlayback', { entity: { kind: 'TRACK', providerId: TRACK_ID }, playedMs: 22_400, durationMs: LENGTH_MS });
    await call('audio.reportPlayback', { entity: { kind: 'TRACK', providerId: TRACK_ID }, playedMs: 500 });
    await call('audio.reportPlayback', { entity: { kind: 'TRACK', providerId: TRACK_ID }, playedMs: 61_000 });
    const [first, second] = calls;
    assert.equal(calls.length, 2);
    assert.equal(first.method, 'POST');
    assert.deepEqual({ ...first.body, device_id: undefined, play_timestamp: undefined }, {
      device_id: undefined,
      heatmap: [],
      item_id: Number(TRACK_ID),
      offline: false,
      play_time_seconds: 22,
      play_timestamp: undefined,
      stream_quality: '128k.aac.m3u8',
    });
    assert.match(first.body.device_id, /^[0-9a-f]{16}$/);
    assert.equal(second.body.device_id, first.body.device_id);
    assert.ok(Math.abs(first.body.play_timestamp - (before - 22)) <= 1);
  });
});
