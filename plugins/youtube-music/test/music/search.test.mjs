// Search and suggestions, against YouTube Music's answers for "paul kalkbrenner", recorded and trimmed.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { blocks, collection, offlinePlugin } from './helpers.mjs';

const SONGS = 'EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D';
const VIDEOS = 'EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D';
const ALBUMS = 'EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D';
const ARTISTS = 'EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D';
const COMMUNITY = 'EgeKAQQoAEABagoQAxAEEAoQCRAF';

const routes = [
  [(call) => call.endpoint === 'search' && call.query.continuation, 'search_songs_continuation'],
  [(call) => call.endpoint === 'search' && call.body.params === SONGS, 'search_songs'],
  [(call) => call.endpoint === 'search' && call.body.params === ALBUMS, 'search_albums'],
  [(call) => call.endpoint === 'search' && !call.body.params, 'search_summary'],
  [(call) => call.endpoint === 'music/get_search_suggestions', 'suggestions'],
];

describe('search without a filter', async () => {
  const { call, calls } = offlinePlugin(routes);
  const page = await call('metadata.search', { query: 'paul kalkbrenner' });

  test('is searched anonymously, as the app did', () => {
    assert.deepEqual(calls.map((it) => [it.endpoint, it.body.query, it.signed]), [['search', 'paul kalkbrenner', false]]);
  });

  test("opens with the top result, whose songs are that artist's", () => {
    const top = blocks(page)[0];
    assert.equal(top.header.title, 'Top result');
    assert.equal(top.layout, 'HORIZONTAL_SHELF');
    assert.deepEqual(top.items[0].entity, { kind: 'ARTIST', providerId: 'UC1Vv9yDroSOtkgfgV8nxZZg' });
    assert.equal(top.items[0].view, 'ARTIST_PORTRAIT');
    const songs = top.items.slice(1);
    assert.deepEqual(songs.map((item) => item.title), ['Revolte', 'Sky & Sand', 'Feed Your Head']);
    assert.ok(songs.every((item) => item.track && item.artists[0].name === 'Paul Kalkbrenner'));
    assert.deepEqual(songs.map((item) => item.durationSeconds), [160, 240, 347]);
  });

  test('then every kind of result YouTube ranked, each showing all through its filter', () => {
    const kinds = Object.fromEntries(blocks(page).slice(1).map((block) => [block.header.title, block.showAllFilterId]));
    assert.equal(kinds.Songs, SONGS);
    assert.equal(kinds.Videos, VIDEOS);
    assert.equal(kinds.Albums, ALBUMS);
    assert.equal(kinds.Artists, ARTISTS);
    assert.equal(kinds['Community playlists'], COMMUNITY);
    assert.ok(!('Podcasts' in kinds) && !('Episodes' in kinds) && !('Profiles' in kinds));
  });

  test('songs and videos are playable rows; albums, artists and playlists are cards to open', () => {
    const songs = collection(page, 'Songs');
    assert.equal(songs.layout, 'TRACK_TABLE');
    assert.ok(songs.items.every((item) => item.entity.kind === 'TRACK' && item.track && !item.track.hasVideo));
    assert.deepEqual(songs.items[0].artists.map((artist) => artist.name), ['Paul Kalkbrenner']);
    const videos = collection(page, 'Videos');
    assert.ok(videos.items.every((item) => item.entity.kind === 'MUSIC_VIDEO' && item.track.hasVideo));
    const albums = collection(page, 'Albums');
    assert.equal(albums.layout, 'HORIZONTAL_SHELF');
    assert.ok(albums.items.every((item) => item.entity.kind === 'ALBUM' && !item.track));
    assert.deepEqual(albums.items[0].artists.map((artist) => artist.name), ['Paul Kalkbrenner']);
    assert.ok(collection(page, 'Artists').items.every((item) => item.view === 'ARTIST_PORTRAIT'));
  });

  test("offers the app's filters in a fixed order, labelled as YouTube labels them", () => {
    assert.deepEqual(
      page.filters.options.map((option) => option.label),
      ['Songs', 'Videos', 'Albums', 'Artists', 'Featured playlists', 'Community playlists'],
    );
    assert.equal(page.filters.options[0].id, SONGS);
  });

  test('item ids are unique on the page', () => {
    const ids = blocks(page).flatMap((block) => block.items.map((item) => item.id));
    assert.equal(new Set(ids).size, ids.length);
  });
});

describe('search with a filter', async () => {
  const { call, calls } = offlinePlugin(routes);
  const page = await call('metadata.search', { query: 'paul kalkbrenner', filterId: SONGS });

  test('is one table of songs with their artists, album and length, paging on', () => {
    assert.equal(calls[0].body.params, SONGS);
    const results = blocks(page)[0];
    assert.equal(results.id, 'results');
    assert.equal(results.layout, 'TRACK_TABLE');
    assert.equal(results.header.title, 'Songs');
    const first = results.items[0];
    assert.equal(first.title, 'Sky & Sand');
    assert.deepEqual(first.artists.map((artist) => artist.name), ['Paul Kalkbrenner']);
    assert.equal(first.album, 'Berlin Calling (The Soundtrack By Paul Kalkbrenner)');
    assert.equal(first.track.albumRef.kind, 'ALBUM');
    assert.equal(first.durationSeconds, 240);
    assert.equal(first.track.durationMs, 240000);
    assert.ok(page.nextCursor);
  });

  test('continues the same list from its cursor', async () => {
    const next = await call('metadata.search', { query: 'paul kalkbrenner', filterId: SONGS, cursor: page.nextCursor });
    const last = calls.at(-1);
    assert.equal(last.query.continuation, page.nextCursor);
    assert.equal(last.query.ctoken, page.nextCursor);
    assert.equal(next.blocks[0].id, 'results');
    assert.ok(next.blocks[0].items.length > 0);
    assert.ok(next.blocks[0].items.every((item) => item.track));
  });

  test("an album list credits each album's artists, not its type", async () => {
    const albums = await call('metadata.search', { query: 'paul kalkbrenner', filterId: ALBUMS });
    const items = blocks(albums)[0].items;
    assert.ok(items.every((item) => item.entity.kind === 'ALBUM'));
    assert.deepEqual(items[0].artists.map((artist) => artist.name), ['Paul Kalkbrenner']);
    assert.equal(albums.nextCursor ?? null, null);
  });
});

describe('a summary served as titled shelves', () => {
  test('keeps their titles and maps each Show all to its filter', async () => {
    const row = (title, videoId, line) => ({
      musicResponsiveListItemRenderer: {
        playlistItemData: { videoId },
        flexColumns: [
          { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: title }] } } },
          { musicResponsiveListItemFlexColumnRenderer: { text: { runs: line } } },
        ],
      },
    });
    const response = {
      contents: {
        tabbedSearchResultsRenderer: {
          tabs: [
            {
              tabRenderer: {
                content: {
                  sectionListRenderer: {
                    contents: [
                      {
                        musicShelfRenderer: {
                          title: { runs: [{ text: 'Liedjes' }] },
                          contents: [row('Aaron', 'a1', [{ text: 'Liedje' }, { text: ' • ' }, { text: 'Paul Kalkbrenner' }, { text: ' • ' }, { text: '4:00' }])],
                          bottomEndpoint: { searchEndpoint: { query: 'x', params: 'EgWKAQIIAWoSEAUQChAEEAkQAxAOEBUQERAQ' } },
                        },
                      },
                    ],
                  },
                },
              },
            },
          ],
        },
      },
    };
    const { call } = offlinePlugin([[(it) => it.endpoint === 'search', response]]);
    const page = await call('metadata.search', { query: 'x' });
    const shelf = blocks(page)[0];
    assert.equal(shelf.header.title, 'Liedjes');
    assert.equal(shelf.showAllFilterId, SONGS);
    assert.deepEqual(shelf.items[0].artists, [{ name: 'Paul Kalkbrenner' }]);
    assert.equal(shelf.items[0].durationSeconds, 240);
  });
});

describe('suggestions', () => {
  test('are the queries YouTube Music suggests, in order', async () => {
    const { call, calls } = offlinePlugin(routes);
    const { queries } = await call('metadata.suggest', { query: 'paul kalk' });
    assert.equal(calls[0].body.input, 'paul kalk');
    assert.equal(queries[0], 'paul kalkbrenner');
    assert.ok(queries.length >= 5);
    assert.ok(queries.every((query) => typeof query === 'string' && query.length > 0));
  });
});
