// Artist, album and playlist pages and their tracks, against anonymous YouTube Music pages, trimmed
// (the app's YouTubePageMapperTest and YouTubeMusicProviderTest, ported), and a live album.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { SIGNED_IN, blocks, browseOf, collection, continuationOf, fixture, header, nextOf, offlinePlugin } from './helpers.mjs';

const ARTIST = { kind: 'ARTIST', providerId: 'UCQB_EBQnT6ImE-niLXQCTOQ' };
const ALBUM = { kind: 'ALBUM', providerId: 'MPREb_2IyzsjcMyu2' };
const PLAYLIST = { kind: 'PLAYLIST', providerId: 'PLEvb08V8d8f8' };

/** The playlist's first three rows, served as a continuation that names the next page. */
function playlistContinuation() {
  const rows = fixture('youtube_music_playlist').contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.contents[0].musicPlaylistShelfRenderer.contents.slice(0, 3);
  return {
    onResponseReceivedActions: [
      { appendContinuationItemsAction: { continuationItems: [...rows, { continuationItemRenderer: { continuationEndpoint: { continuationCommand: { token: 'next-page' } } } }] } },
    ],
  };
}

const routes = [
  [browseOf(ARTIST.providerId), 'youtube_music_artist'],
  [browseOf(ALBUM.providerId), 'youtube_music_album'],
  [browseOf(`VL${PLAYLIST.providerId}`), 'youtube_music_playlist'],
  [browseOf('VLOLAK5uy_mrZZBKDBYhlaDLazNecBFv5fFHcpDHjPw'), 'youtube_music_playlist'],
  [continuationOf('more'), () => playlistContinuation()],
];

describe('artist', async () => {
  const { call } = offlinePlugin(routes);
  const page = await call('metadata.entity', { entity: ARTIST });

  test('an artist opens with its portrait, its audience and its station', () => {
    const head = page.blocks[0];
    assert.equal(head.type, 'header');
    assert.equal(head.style, 'PORTRAIT');
    assert.equal(head.title, 'Massano');
    assert.deepEqual(head.details, ['759K monthly audience']);
    assert.ok(head.artwork?.url);
    assert.deepEqual(head.station, { kind: 'RADIO', providerId: 'RDEMrKT7MiRTXV0lBmFLcMBxPg' });
  });

  test("an artist's top songs are a table with plays and album, and all songs one step away", () => {
    const top = blocks(page)[0];
    assert.equal(top.header.title, 'Top songs');
    assert.equal(top.layout, 'TRACK_TABLE');
    const first = top.items[0];
    assert.equal(first.title, 'The Feeling (2022 Remaster)');
    assert.equal(first.subtitle, 'Massano • 14M plays');
    assert.equal(first.album, 'In My System EP');
    assert.equal(first.ordinal, undefined);
    assert.deepEqual(top.showAll, { kind: 'PLAYLIST', providerId: 'OLAK5uy_mrZZBKDBYhlaDLazNecBFv5fFHcpDHjPw' });
  });

  test("an artist's shelves follow in order, videos wide and similar artists round", () => {
    const shelves = blocks(page).slice(1);
    assert.deepEqual(
      shelves.map((shelf) => shelf.header?.title),
      ['Albums', 'Singles & EPs', 'Videos', 'Live performances', 'Featured on', 'Playlists by Massano', 'Fans might also like'],
    );
    assert.deepEqual([...new Set(collection(page, 'Videos').items.map((item) => item.view))], ['LANDSCAPE_CARD']);
    assert.deepEqual([...new Set(collection(page, 'Fans might also like').items.map((item) => item.view))], ['ARTIST_PORTRAIT']);
  });

  test("an artist's tracks are its top songs, through their playlist", async () => {
    const list = await call('metadata.tracks', { entity: ARTIST });
    assert.deepEqual(list.source, { kind: 'PLAYLIST', providerId: 'OLAK5uy_mrZZBKDBYhlaDLazNecBFv5fFHcpDHjPw' });
    assert.equal(list.tracks.length, 12);
  });
});

describe('album', async () => {
  const { call } = offlinePlugin(routes);
  const page = await call('metadata.entity', { entity: ALBUM });

  test('an album shows its cover, facts and artist, and numbers its tracks', () => {
    const head = page.blocks[0];
    assert.equal(head.style, 'COVER');
    assert.equal(head.title, 'NEWORLD II');
    assert.deepEqual(head.details, ['Album • 2026', '15 songs • 44 minutes']);
    assert.equal(head.attribution.name, 'Argy');
    assert.deepEqual(head.attribution.entity, { kind: 'ARTIST', providerId: 'UCEIFXVEf5DULGLCKSRJbabQ' });
    assert.deepEqual(head.tracks, { kind: 'PLAYLIST', providerId: 'OLAK5uy_mjsgo4gplxlaTC9QFVbdV3l8YOYeDsO9k' });

    const tracks = blocks(page)[0];
    assert.equal(tracks.layout, 'TRACK_TABLE');
    assert.equal(tracks.items.length, 15);
    assert.deepEqual(tracks.items.map((item) => item.ordinal), Array.from({ length: 15 }, (_, index) => index + 1));
    const first = tracks.items[0];
    assert.equal(first.title, 'DONA');
    assert.equal(first.subtitle, 'Argy & Omiki • 2M plays');
    assert.equal(first.durationSeconds, 170);
    assert.deepEqual(first.artists.map((artist) => artist.name), ['Argy', 'Omiki']);
  });

  test("an album's track that names no artist is credited to the album's artist, never to its play count", () => {
    const items = blocks(page)[0].items;
    assert.ok(!items.flatMap((item) => item.artists).some((artist) => artist.name === '2M plays'));
    assert.ok(items.every((item) => item.artists.length > 0));
  });

  test('an album is followed by releases like it', () => {
    assert.ok(blocks(page).some((block) => block.header?.title === 'Releases for you'));
  });

  test("an album's tracks are the album's: its name, its cover, its numbering", async () => {
    const list = await call('metadata.tracks', { entity: ALBUM });
    assert.equal(list.tracks.length, 15);
    assert.deepEqual(list.source, ALBUM);
    for (const [index, track] of list.tracks.entries()) {
      assert.equal(track.album, 'NEWORLD II');
      assert.deepEqual(track.albumRef, ALBUM);
      assert.equal(track.trackNumber, index + 1);
      assert.equal(track.artwork.url, page.blocks[0].artwork.url);
      assert.equal(track.ids.ytm, track.ref.providerId);
      assert.ok(track.durationMs > 0);
    }
  });
});

describe('playlist', async () => {
  const { call } = offlinePlugin(routes);
  const page = await call('metadata.entity', { entity: PLAYLIST });

  test('a playlist describes itself and lists its tracks with their albums, unnumbered', () => {
    const head = page.blocks[0];
    assert.equal(head.title, 'Melodic Selections');
    assert.equal(head.attribution.name, 'Massano');
    assert.ok(head.attribution.avatar?.url);
    assert.deepEqual(head.attribution.entity, { kind: 'ARTIST', providerId: 'UC80nHYohajSIsAXGipxVwyA' });
    assert.equal(head.details[0], 'Playlist • 2026');
    assert.ok(head.description);
    assert.deepEqual(head.tracks, PLAYLIST);

    const tracks = blocks(page)[0];
    assert.equal(tracks.items.length, 12);
    const first = tracks.items[0];
    assert.equal(first.title, 'Beyond Today');
    assert.equal(first.subtitle, 'Massano');
    assert.equal(first.album, 'Beyond Today');
    assert.equal(first.ordinal, undefined);
    assert.equal(first.durationSeconds, 216);
    assert.equal(first.track.album, 'Beyond Today');
    assert.equal(first.track.albumRef.kind, 'ALBUM');
  });

  test("a long playlist's further tracks extend its tracks block and name the next page", async () => {
    const next = await call('metadata.entity', { entity: PLAYLIST, cursor: 'more' });
    assert.equal(next.blocks.length, 1);
    assert.equal(next.blocks[0].id, 'tracks');
    assert.equal(next.blocks[0].items[0].title, 'Beyond Today');
    assert.equal(next.blocks[0].items.length, 3);
    assert.equal(next.nextCursor, 'next-page');
  });

  test("a playlist's tracks page on with the same continuation", async () => {
    const first = await call('metadata.tracks', { entity: PLAYLIST });
    assert.equal(first.tracks.length, 12);
    assert.equal(first.next ?? null, null);
    const more = await call('metadata.tracks', { entity: PLAYLIST, cursor: JSON.stringify({ type: 'browse', continuation: 'more' }) });
    assert.equal(more.tracks.length, 3);
    assert.equal(JSON.parse(more.next).continuation, 'next-page');
  });
});

describe('page requests', () => {
  test("pages browse by the entity's id, a playlist through its VL page, a cursor as a continuation", async () => {
    const { call, calls } = offlinePlugin(routes);
    await call('metadata.entity', { entity: ARTIST });
    await call('metadata.entity', { entity: ALBUM });
    await call('metadata.entity', { entity: PLAYLIST });
    await call('metadata.entity', { entity: PLAYLIST, cursor: 'more' });
    assert.deepEqual(
      calls.map((it) => it.body.browseId ?? it.body.continuation),
      [ARTIST.providerId, ALBUM.providerId, `VL${PLAYLIST.providerId}`, 'more'],
    );
  });

  test("a signed-in account's pages are read as that account", async () => {
    const { call, calls } = offlinePlugin(routes, { secrets: SIGNED_IN });
    await call('metadata.entity', { entity: ARTIST });
    assert.ok(calls.every((it) => it.signed));
  });

  test('there is no page for a track', async () => {
    const { call } = offlinePlugin(routes);
    await assert.rejects(call('metadata.entity', { entity: { kind: 'TRACK', providerId: 'v1' } }), { code: 'UNSUPPORTED' });
  });

  test('a mix YouTube serves no page for is its watch queue, paging on', async () => {
    const mix = { kind: 'MIX', providerId: 'RDAMVMi1OpHoRFGAw' };
    const { call, calls } = offlinePlugin([[nextOf({ playlistId: mix.providerId }), 'next_track']]);
    const page = await call('metadata.entity', { entity: mix });
    assert.equal(header(page).title, 'Battery Park Mix');
    assert.deepEqual(header(page).tracks, mix);
    assert.equal(blocks(page)[0].items.length, 6);
    assert.ok(blocks(page)[0].items.every((item) => item.track));
    await call('metadata.entity', { entity: mix, cursor: page.nextCursor });
    assert.equal(calls.at(-1).body.continuation, JSON.parse(page.nextCursor).continuation);
    assert.equal(calls.at(-1).body.playlistId, mix.providerId);
  });
});

describe('a live album page (7, recorded)', async () => {
  const album = { kind: 'ALBUM', providerId: 'MPREb_KHcXeTpvxEU' };
  const { call } = offlinePlugin([[browseOf(album.providerId), 'album_7']]);
  const page = await call('metadata.entity', { entity: album });

  test('the header names the artist and the playlist that plays it', () => {
    const head = header(page);
    assert.equal(head.title, '7');
    assert.equal(head.attribution.name, 'Paul Kalkbrenner');
    assert.equal(head.tracks.kind, 'PLAYLIST');
    assert.ok(head.tracks.providerId.startsWith('OLAK5uy_'));
  });

  test("every track is credited to the album's artist and numbered", () => {
    const tracks = blocks(page)[0];
    assert.equal(tracks.items.length, 12);
    assert.ok(tracks.items.every((item) => item.artists.map((artist) => artist.name).includes('Paul Kalkbrenner')));
    assert.ok(tracks.items.every((item) => !/plays$/.test(item.artists[0].name)));
    assert.equal(tracks.items[11].ordinal, 12);
  });
});
