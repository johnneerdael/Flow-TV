import assert from 'node:assert/strict';
import test from 'node:test';
import { offlinePlugin, pathIs } from './helpers.mjs';

test('matching a Spotify track asks only for tracks and preserves playable descriptors', async () => {
  const { call, calls } = offlinePlugin({ routes: [[pathIs('/v4/catalog/search/'), { body: { tracks: [
    { id: 123, name: 'Prophecy', mix_name: 'Original Mix', artists: [{ id: 1, name: 'Anyma' }], length_ms: 143000, isrc: 'GB123', is_available_for_streaming: true },
    { id: 124, name: 'Unavailable', is_available_for_streaming: false },
  ] } }]] });
  const result = await call('audio.match', { track: { ref: { kind: 'TRACK', providerId: 'spotify:song' }, title: 'Prophecy', artists: [{ name: 'Anyma' }], durationMs: 143000 } });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].query.type, 'tracks');
  assert.equal(calls[0].query.q, 'Anyma Prophecy');
  assert.equal(result.candidates.length, 1);
  assert.deepEqual(result.candidates[0].ids, { beatport: '123', isrc: 'GB123' });
  assert.equal(result.candidates[0].durationMs, 143000);
  assert.equal(result.candidates[0].title, 'Prophecy (Original Mix)');
});

test('an empty descriptor returns no candidates without a request', async () => {
  const { call, calls } = offlinePlugin();
  assert.deepEqual(await call('audio.match', { track: { ref: { kind: 'TRACK', providerId: 'spotify:song' }, title: '' } }), { candidates: [] });
  assert.equal(calls.length, 0);
});
