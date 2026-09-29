// Smoke tests against live YouTube Music, anonymous; run with LIVE=1. They check shapes and the
// behaviours that matter (paging, filters, radios that continue rather than repeat), not exact data.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { loadPlugin } from '@milkbeat/mbplugin/harness';
import { LIVE, PLUGIN_DIR, blocks, header } from './helpers.mjs';

const SONGS = 'EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D';

describe('live YouTube Music', { skip: !LIVE && 'set LIVE=1 to run against YouTube' }, () => {
  const { call } = loadPlugin(PLUGIN_DIR);
  const ids = (list) => list.tracks.map((track) => track.ref.providerId);

  test('home: chips, several shelf styles, and a further page', async () => {
    const home = await call('metadata.home', {});
    assert.ok(home.filters.options.length >= 3);
    const layouts = new Set(blocks(home).map((block) => block.layout));
    assert.ok(layouts.has('HORIZONTAL_SHELF') && layouts.has('MULTI_COLUMN_LIST'), [...layouts].join());
    assert.ok(blocks(home).some((block) => block.items.some((item) => item.track)));
    assert.ok(home.nextCursor);
    const more = await call('metadata.home', { cursor: home.nextCursor });
    assert.ok(blocks(more).length > 0);
    const mood = await call('metadata.home', { filterId: home.filters.options[1].id });
    assert.ok(blocks(mood).length > 0);
  });

  test('search "paul kalkbrenner 7" finds the album, which opens with its twelve tracks', async () => {
    const search = await call('metadata.search', { query: 'paul kalkbrenner 7' });
    const album = blocks(search)
      .flatMap((block) => block.items)
      .find((item) => item.entity.kind === 'ALBUM' && item.title === '7');
    assert.ok(album, 'the album 7');
    const page = await call('metadata.entity', { entity: album.entity });
    assert.equal(header(page).attribution.name, 'Paul Kalkbrenner');
    const tracks = await call('metadata.tracks', { entity: album.entity });
    assert.ok(tracks.tracks.length >= 12);
    assert.ok(tracks.tracks.every((track) => track.album === '7' && track.ids.ytm && track.artists.length > 0));

    const radio = await call('metadata.radio', { seed: album.entity });
    const own = new Set(ids(tracks));
    assert.ok(radio.tracks.length > 10);
    assert.ok(ids(radio).filter((id) => own.has(id)).length <= 2, 'an album radio is similar content, not the album');
  });

  test("an artist: portrait with its station, top songs, and a 100-track playlist that pages on", async () => {
    const artist = { kind: 'ARTIST', providerId: 'UC1Vv9yDroSOtkgfgV8nxZZg' };
    const page = await call('metadata.entity', { entity: artist });
    assert.equal(header(page).style, 'PORTRAIT');
    assert.equal(header(page).station.kind, 'RADIO');
    const top = blocks(page)[0];
    assert.equal(top.layout, 'TRACK_TABLE');
    assert.equal(top.showAll.kind, 'PLAYLIST');

    const playlist = await call('metadata.entity', { entity: top.showAll });
    assert.equal(blocks(playlist)[0].items.length, 100);
    assert.ok(playlist.nextCursor);
    const more = await call('metadata.entity', { entity: top.showAll, cursor: playlist.nextCursor });
    assert.equal(more.blocks[0].id, 'tracks');
    assert.ok(more.blocks[0].items.length > 0);

    const tracks = await call('metadata.tracks', { entity: top.showAll });
    assert.equal(tracks.tracks.length, 100);
    const rest = await call('metadata.tracks', { entity: top.showAll, cursor: tracks.next });
    assert.ok(rest.tracks.length > 0);

    const station = await call('metadata.radio', { seed: header(page).station });
    assert.ok(station.tracks.length > 10);
  });

  test('search with a filter pages on; suggestions come back', async () => {
    const songs = await call('metadata.search', { query: 'paul kalkbrenner', filterId: SONGS });
    assert.ok(blocks(songs)[0].items.every((item) => item.track && item.artists.length > 0));
    const more = await call('metadata.search', { query: 'paul kalkbrenner', filterId: SONGS, cursor: songs.nextCursor });
    assert.ok(blocks(more)[0].items.length > 0);
    const suggestions = await call('metadata.suggest', { query: 'paul kalk' });
    assert.ok(suggestions.queries.length > 0);
  });

  test("a track radio is YouTube's mix of it, and continues", async () => {
    const radio = await call('metadata.radio', { seed: { kind: 'TRACK', providerId: 'i1OpHoRFGAw' } });
    assert.ok(radio.tracks.length > 10);
    assert.ok(!ids(radio).includes('i1OpHoRFGAw'));
    assert.equal(radio.source.providerId, 'RDAMVMi1OpHoRFGAw');
    const more = await call('metadata.radio', { seed: { kind: 'TRACK', providerId: 'i1OpHoRFGAw' }, cursor: radio.next });
    assert.ok(more.tracks.length > 0);
  });
});
