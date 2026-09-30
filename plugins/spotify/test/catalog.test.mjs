import assert from 'node:assert/strict';
import test from 'node:test';
import { ALBUM, ARTIST, LIKED, PLAYLIST, collections, fixture, header, offline, op, table } from './helpers.mjs';

test('Home preserves Spotify shelf order and mixed card styles, omitting non-music items', async () => {
  const p = offline();
  const page = await p.call('metadata.home', {});
  const blocks = collections(page);
  assert.ok(blocks.some((b) => b.header?.title === 'Recommended Stations'));
  assert.ok(blocks.some((b) => b.header?.title === 'Best of artists'));
  assert.ok(blocks.flatMap((b) => b.items).some((i) => i.view === 'ARTIST_PORTRAIT'));
  assert.ok(blocks.flatMap((b) => b.items).every((i) => ['TRACK', 'ALBUM', 'ARTIST', 'PLAYLIST', 'MIX'].includes(i.entity.kind)));
  assert.equal(p.calls.find(op('home')).variables.facet, 'music-chip');
  assert.deepEqual(page.filters.options.map((f) => f.id), ['music-chip', 'music-following-chip']);
  const mixShelf = blocks.find((b) => b.header?.title === 'Your top mixes');
  assert.ok(mixShelf.showAll);
  assert.equal(mixShelf.showAll.kind, 'MIX');
  await p.call('metadata.home', {});
  assert.equal(p.calls.filter(op('home')).length, 1);
});

test('Artist details and playable top tracks share one fetch', async () => {
  const p = offline();
  const page = await p.call('metadata.entity', { entity: ARTIST });
  assert.equal(header(page).title, '19:26');
  assert.equal(header(page).style, 'PORTRAIT');
  assert.ok(header(page).details.some((d) => d.includes('monthly listeners')));
  assert.equal(table(page).items[0].title, 'Prophecy');
  assert.equal(table(page).items[0].track.durationMs, 142615);
  assert.ok(collections(page).some((b) => b.header?.title === 'Fans also like'));
  const result = await p.call('metadata.tracks', { entity: ARTIST });
  assert.equal(result.tracks[0].ref.providerId, table(page).items[0].entity.providerId);
  assert.equal(p.calls.filter(op('queryArtistOverview')).length, 1);
});

test('Album rows inherit album metadata needed by the YouTube matcher', async () => {
  const p = offline();
  const page = await p.call('metadata.entity', { entity: ALBUM });
  const track = table(page).items[0].track;
  assert.equal(header(page).title, 'Prophecy');
  assert.equal(track.album, 'Prophecy');
  assert.deepEqual(track.albumRef, ALBUM);
  assert.equal(track.artists[0].name, 'Anyma');
  assert.ok(track.artwork.url.includes('scdn.co'));
  assert.equal(track.trackNumber, 1);
  assert.equal((await p.call('metadata.tracks', { entity: ALBUM })).tracks[0].durationMs, 142615);
});

test('Playlist duration uses trackDuration and duplicate occurrences have distinct row keys', async () => {
  const data = fixture('playlist');
  const content = data.data.playlistV2.content;
  content.items = [content.items[0], content.items[0], { itemV2: { data: { __typename: 'Episode', name: 'Podcast', uri: 'spotify:episode:fixture' } } }];
  content.totalCount = 4;
  const p = offline({ routes: [[op('fetchPlaylist'), { body: data }]] });
  const page = await p.call('metadata.entity', { entity: PLAYLIST });
  const rows = table(page).items;
  assert.equal(rows.length, 2);
  assert.notEqual(rows[0].id, rows[1].id);
  assert.equal(rows[0].track.durationMs, 183281);
  assert.equal(header(page).tracks.providerId, PLAYLIST.providerId);
  assert.ok(page.nextCursor);
  await p.call('metadata.entity', { entity: PLAYLIST, cursor: page.nextCursor });
  assert.equal(p.calls.filter(op('fetchPlaylist'))[1].variables.offset, 3);
  await assert.rejects(p.call('metadata.entity', { entity: ALBUM, cursor: page.nextCursor }), { code: 'UNSUPPORTED' });
});

test('Search filters page only their selected music kind', async () => {
  const p = offline();
  const page = await p.call('metadata.search', { query: '19:26', filterId: 'tracks' });
  assert.equal(collections(page).length, 1);
  assert.ok(table(page).items.length > 0);
  assert.ok(table(page).items.every((i) => i.track.artists.length > 0));
  assert.ok(page.nextCursor);
  await p.call('metadata.search', { query: '19:26', filterId: 'tracks', cursor: page.nextCursor });
  assert.equal(p.calls.filter(op('searchDesktop'))[1].variables.offset, fixture('search').data.searchV2.tracksV2.items.length);
  await assert.rejects(p.call('metadata.search', { query: 'other', filterId: 'tracks', cursor: page.nextCursor }), { code: 'UNSUPPORTED' });
});

test('Library supports albums and artists, and Liked Songs return playable descriptors', async () => {
  const album = fixture('album').data.albumUnion;
  const artist = fixture('artist').data.artistUnion;
  const track = fixture('search').data.searchV2.tracksV2.items[0].item;
  const p = offline({ routes: [
    [op('libraryV3'), { body: { data: { me: { libraryV3: { totalCount: 2, items: [{ item: { data: album } }, { item: { data: artist } }] } } } } }],
    [op('fetchLibraryTracks'), { body: { data: { me: { library: { tracks: { totalCount: 1, items: [{ track }] } } } } } }],
  ] });
  const page = await p.call('metadata.library', {});
  const cards = collections(page).flatMap((b) => b.items);
  assert.ok(cards.some((i) => i.entity.kind === 'ARTIST'));
  assert.ok(cards.some((i) => i.entity.kind === 'ALBUM'));
  assert.ok(cards.some((i) => i.entity.providerId === LIKED.providerId));
  assert.equal((await p.call('metadata.tracks', { entity: LIKED })).tracks[0].title, 'On The Other Side');
  await p.call('metadata.library', { section: 'artists' });
  assert.deepEqual(p.calls.filter(op('libraryV3'))[1].variables.filters, ['Artists']);
});

test('Signed out Home and Library ask for sign-in before calling Spotify', async () => {
  const p = offline({ secrets: {} });
  await assert.rejects(p.call('metadata.home', {}), { code: 'SIGN_IN_REQUIRED' });
  await assert.rejects(p.call('metadata.library', {}), { code: 'SIGN_IN_REQUIRED' });
  assert.equal(p.calls.length, 0);
});

test('Rate limits are surfaced without immediate retry', async () => {
  const p = offline({ routes: [[op('getAlbum'), { status: 429, headers: { 'retry-after': '4' }, body: {} }]] });
  await assert.rejects(p.call('metadata.entity', { entity: ALBUM }), { code: 'RATE_LIMITED', retryAfterMs: 4000 });
  assert.equal(p.calls.filter(op('getAlbum')).length, 1);
});

test('A rotated query hash is refreshed once and concurrent identical requests are deduped', async () => {
  let attempts = 0;
  const hash = 'a'.repeat(64);
  const p = offline({ routes: [
    [(c) => c.path.endsWith('/hashes.json'), { body: { getAlbum: hash } }],
    [op('getAlbum'), () => ++attempts === 1 ? { body: { errors: [{ message: 'PersistedQueryNotFound' }] } } : { body: fixture('album') }],
  ] });
  const [a, b] = await Promise.all([p.call('metadata.entity', { entity: ALBUM }), p.call('metadata.entity', { entity: ALBUM })]);
  assert.equal(header(a).title, header(b).title);
  assert.equal(attempts, 2);
  assert.equal(p.calls.filter(op('getAlbum'))[1].hash, hash);
});

test('Home section expansion reads homeSections and rejects an unavailable section', async () => {
  const section = fixture('home').data.home.sectionContainer.sections.items[2];
  const ref = { kind: 'MIX', providerId: section.uri };
  const p = offline({ routes: [[op('homeSection'), { body: { data: { homeSections: { sections: [section] } } } }]] });
  const page = await p.call('metadata.entity', { entity: ref });
  assert.equal(collections(page)[0].header.title, 'Your top mixes');
  assert.ok(collections(page)[0].items.length > 0);
  const missing = offline({ routes: [[op('homeSection'), { body: { data: { homeSections: { sections: [{ __typename: 'NotFound' }] } } } }]] });
  await assert.rejects(missing.call('metadata.entity', { entity: ref }), { code: 'NOT_FOUND' });
});

test('Individual track lookup reads firstArtist and otherArtists for matching', async () => {
  const p = offline();
  const result = await p.call('metadata.tracks', { entity: { kind: 'TRACK', providerId: 'spotify:track:7K4xmQ87a8CFPZSTaIB93z' } });
  assert.deepEqual(result.tracks[0].artists.map((a) => a.name), ['Anyma', '19:26', 'Baset']);
});
