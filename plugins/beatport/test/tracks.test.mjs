// Tracks from recorded fixtures: what a row and a queued track carry, and Play all a page at a time.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { IDS } from '../scripts/scenario.mjs';
import { API, block, offlinePlugin, recorded } from './helpers.mjs';

const ref = (kind, providerId) => ({ kind, providerId });

describe('a track', async () => {
  const { call } = offlinePlugin();
  const page = await call('metadata.entity', { entity: ref('PLAYLIST', `chart:${IDS.longChart}`) });
  const source = recorded(`${API}/v4/catalog/charts/${IDS.longChart}/tracks/?page=1&per_page=100`).results;
  const items = block(page, 'tracks').items;

  test('is titled as Beatport prints it, with its mix', () => {
    items.forEach((item, index) => {
      const track = source[index];
      assert.equal(item.title, track.mix_name ? `${track.name} (${track.mix_name})` : track.name);
      assert.equal(item.track.title, item.title);
    });
  });

  test('queues with its Beatport id and ISRC, duration, release and the release’s cover', () => {
    const [item] = items;
    const [track] = source;
    const descriptor = item.track;
    assert.deepEqual(descriptor.ref, ref('TRACK', String(track.id)));
    assert.deepEqual(descriptor.ids, { beatport: String(track.id), isrc: track.isrc });
    assert.equal(descriptor.durationMs, track.length_ms);
    assert.equal(descriptor.album, track.release.name);
    assert.deepEqual(descriptor.albumRef, ref('ALBUM', String(track.release.id)));
    assert.equal(descriptor.artwork.url, track.release.image.dynamic_uri.replace('{w}x{h}', '1400x1400'));
    assert.equal(item.artwork.url, track.release.image.dynamic_uri.replace('{w}x{h}', '600x600'));
    assert.equal(descriptor.year, Number(track.new_release_date.slice(0, 4)));
    assert.equal(item.durationSeconds, Math.round(track.length_ms / 1000));
  });

  test('credits its artists, then its remixers, each linking their page', () => {
    items.forEach((item, index) => {
      const track = source[index];
      const people = [...track.artists, ...track.remixers.filter((remixer) => !track.artists.some((artist) => artist.id === remixer.id))];
      assert.deepEqual(item.track.artists, people.map((person) => ({ name: person.name, entity: ref('ARTIST', String(person.id)) })));
      assert.equal(item.subtitle, track.artists.map((artist) => artist.name).join(', '));
    });
  });

  test('shows the web store’s columns: label, remixers, genre, tempo and key, release date', () => {
    items.forEach((item, index) => {
      const track = source[index];
      const expected = [
        track.release.label.name,
        track.remixers.length > 0 ? `Remixers: ${track.remixers.map((remixer) => remixer.name).join(', ')}` : undefined,
        track.genre.name,
        `${track.bpm} BPM · ${track.key.name}`,
        track.new_release_date,
      ].filter(Boolean);
      assert.deepEqual(item.details, expected);
    });
  });
});

describe('Play all', async () => {
  const { call, calls } = offlinePlugin();

  test('a long chart comes a hundred at a time, as the chart orders it', async () => {
    const first = await call('metadata.tracks', { entity: ref('PLAYLIST', `chart:${IDS.longChart}`) });
    assert.equal(first.next, '2');
    assert.deepEqual(first.source, ref('PLAYLIST', `chart:${IDS.longChart}`));
    const second = await call('metadata.tracks', { entity: ref('PLAYLIST', `chart:${IDS.longChart}`), cursor: first.next });
    assert.equal(second.next, undefined);
    assert.deepEqual(calls.map((asked) => asked.query.per_page), ['100', '100']);
  });

  test('a release, a curated playlist, a genre (its Top 100) and My Beatport all play', async () => {
    for (const entity of [ref('ALBUM', IDS.release), ref('PLAYLIST', `curated:${IDS.curated}`), ref('MIX', `genre:${IDS.genre}`), ref('MIX', 'my:tracks')]) {
      const list = await call('metadata.tracks', { entity });
      assert.ok(list.tracks.length > 0, JSON.stringify(entity));
      assert.ok(list.tracks.every((track) => track.ids.beatport === track.ref.providerId));
    }
  });

  test('a Top 100 is one page; lists of releases have nothing to play', async () => {
    await assert.rejects(call('metadata.tracks', { entity: ref('MIX', `genre:${IDS.genre}`), cursor: '2' }), { code: 'NOT_FOUND' });
    await assert.rejects(call('metadata.tracks', { entity: ref('MIX', 'top:releases') }), { code: 'UNSUPPORTED' });
    await assert.rejects(call('metadata.tracks', { entity: ref('MIX', `genre:${IDS.genre}:playlists`) }), { code: 'UNSUPPORTED' });
  });
});
