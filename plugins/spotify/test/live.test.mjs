import assert from 'node:assert/strict';
import test from 'node:test';
import { loadPlugin } from '@milkbeat/mbplugin/harness';
import { ALBUM, ARTIST, DIR, PLAYLIST, header, table } from './helpers.mjs';

test('Anonymous Spotify catalog through the real plugin', { skip: process.env.LIVE !== '1', timeout: 90_000 }, async () => {
  const p = loadPlugin(DIR);
  assert.equal((await p.call('signIn.account')).type, 'anonymous');
  const search = await p.call('metadata.search', { query: '19:26', filterId: 'tracks' });
  assert.ok(table(search).items.length > 0);
  for (const entity of [ARTIST, ALBUM, PLAYLIST]) {
    const page = await p.call('metadata.entity', { entity });
    const list = await p.call('metadata.tracks', { entity });
    assert.ok(header(page).title);
    assert.ok(list.tracks.length > 0);
    assert.ok(list.tracks.every((t) => t.ref.providerId.startsWith('spotify:track:') && t.artists.length > 0 && t.durationMs > 0));
  }
  const track = await p.call('metadata.tracks', { entity: { kind: 'TRACK', providerId: 'spotify:track:7K4xmQ87a8CFPZSTaIB93z' } });
  assert.equal(track.tracks[0].title, 'Prophecy');
  assert.equal(track.tracks[0].album, 'Prophecy');
  assert.deepEqual(track.tracks[0].artists.map((a) => a.name), ['Anyma', '19:26', 'Baset']);
});
