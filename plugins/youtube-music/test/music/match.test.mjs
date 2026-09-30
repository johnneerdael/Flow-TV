// audio.match: YouTube Music's songs for a track another plugin describes, searched anonymously.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { offlinePlugin } from './helpers.mjs';

const SONGS = 'EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D';
const spotifyTrack = {
  ref: { kind: 'TRACK', providerId: '4uLU6hMCjMI75M1A2tKUQC' },
  title: 'Sky and Sand',
  artists: [{ name: 'Paul Kalkbrenner' }, { name: 'Fritz Kalkbrenner' }],
  durationMs: 238000,
  ids: { spotify: '4uLU6hMCjMI75M1A2tKUQC' },
};

describe('audio.match', async () => {
  const { call, calls } = offlinePlugin([[(it) => it.endpoint === 'search' && it.body.params === SONGS, 'search_songs']]);
  const matches = await call('audio.match', { track: spotifyTrack });

  test('searches songs anonymously for the first artist and the title', () => {
    assert.deepEqual(calls.map((it) => [it.endpoint, it.body.query, it.body.params, it.signed]), [['search', 'Paul Kalkbrenner Sky and Sand', SONGS, false]]);
  });

  test('offers YouTube’s songs in its order, each playable by its own id', () => {
    assert.ok(matches.candidates.length > 0 && matches.candidates.length <= 10);
    for (const candidate of matches.candidates) {
      assert.equal(candidate.ids.ytm, candidate.ref.providerId);
      assert.ok(candidate.title);
      assert.ok(candidate.artists.length > 0);
    }
    assert.ok(matches.candidates.some((candidate) => candidate.durationMs > 0));
  });

  test('a track with no title or artist is not searched', async () => {
    const empty = offlinePlugin([]);
    assert.deepEqual(await empty.call('audio.match', { track: { ref: { kind: 'TRACK', providerId: 'x' }, title: ' ', ids: { spotify: 'x' } } }), { candidates: [] });
    assert.equal(empty.calls.length, 0);
  });
});
