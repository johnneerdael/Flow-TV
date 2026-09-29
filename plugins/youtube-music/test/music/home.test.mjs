// The home feed, against a signed-in YouTube Music home captured from the web client, trimmed and
// anonymised (the app's YouTubeHomeMapperTest and YouTubeMusicProviderTest, ported).
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { SIGNED_IN, blocks, browseOf, collection, continuationOf, offlinePlugin } from './helpers.mjs';

const routes = [
  [browseOf('FEmusic_home'), 'youtube_music_home'],
  [continuationOf('fixture-continuation'), 'youtube_music_home_continuation'],
];

describe('home', async () => {
  const { call } = offlinePlugin(routes);
  const page = await call('metadata.home', {});

  test('every shelf becomes a collection in the order it is served', () => {
    assert.deepEqual(
      blocks(page).map((block) => block.header?.title),
      ['New releases', 'Quick picks', 'Massano', 'Listen again', 'Music videos for you', 'Live performances'],
    );
    assert.equal(page.nextCursor, 'fixture-continuation');
  });

  test('a list shelf reads as playable track rows in columns', () => {
    const quickPicks = collection(page, 'Quick picks');
    assert.equal(quickPicks.layout, 'MULTI_COLUMN_LIST');
    assert.equal(quickPicks.defaultItemView, 'TRACK_ROW');
    assert.ok(quickPicks.items.every((item) => item.track));
    assert.deepEqual(
      quickPicks.items.map((item) => item.title),
      ['Patient Zero', 'Остави ме на мира', 'new trick', 'BADDIES IN BELGICA', 'Joseph'],
    );
    assert.equal(quickPicks.items[0].subtitle, 'Taylor Swift • 3.9M plays');
    assert.ok(quickPicks.items.every((item) => item.artists.length > 0));
  });

  test('a track row carries a descriptor any audio provider can read', () => {
    const track = collection(page, 'Quick picks').items[0].track;
    assert.equal(track.ref.providerId, track.ids.ytm);
    assert.equal(track.title, 'Patient Zero');
    assert.deepEqual(track.artists.map((artist) => artist.name), ['Taylor Swift']);
    assert.equal(track.artists[0].entity.kind, 'ARTIST');
    assert.ok(track.album);
    assert.equal(track.albumRef.kind, 'ALBUM');
    assert.equal(track.hasVideo, false);
  });

  test('a similar-to shelf keeps its context line, avatar and the artist it names', () => {
    const similar = collection(page, 'Massano');
    assert.equal(similar.header.context, 'SIMILAR TO');
    assert.ok(similar.header.avatar?.url);
    assert.deepEqual(similar.header.target, { kind: 'ARTIST', providerId: 'UCQB_EBQnT6ImE-niLXQCTOQ' });
    assert.equal(similar.layout, 'HORIZONTAL_SHELF');
  });

  test('one shelf mixes round artists, square covers and wide videos', () => {
    const similar = collection(page, 'Massano');
    assert.equal(similar.items[0].entity.kind, 'ARTIST');
    assert.equal(similar.items[0].view, 'ARTIST_PORTRAIT');
    assert.deepEqual([...new Set(similar.items.slice(1).map((item) => item.view))], [undefined]);

    const listenAgain = collection(page, 'Listen again');
    assert.equal(listenAgain.header.context, 'LISTENER');
    assert.equal(listenAgain.header.target?.kind, 'PROFILE');
    assert.deepEqual(
      listenAgain.items.map((item) => item.view),
      [undefined, 'LANDSCAPE_CARD', 'LANDSCAPE_CARD', 'LANDSCAPE_CARD'],
    );
  });

  test('music videos are wide and playable, with pictures', () => {
    const videos = collection(page, 'Music videos for you');
    assert.deepEqual([...new Set(videos.items.map((item) => item.view))], ['LANDSCAPE_CARD']);
    assert.deepEqual([...new Set(videos.items.map((item) => item.entity.kind))], ['MUSIC_VIDEO']);
    assert.ok(videos.items.every((item) => item.track?.hasVideo === true));
  });

  test('podcast episodes are left out of the YouTube catalog', () => {
    const live = collection(page, 'Live performances');
    assert.ok(!live.items.some((item) => item.title === 'Above & Beyond Live at Ziggo Dome, Amsterdam'));
    assert.equal(live.items.length, 4);
  });

  test('albums and playlists open by their browse ids', () => {
    const releases = collection(page, 'New releases');
    assert.deepEqual(
      releases.items.map((item) => item.entity.kind),
      ['ALBUM', 'ALBUM', 'ALBUM', 'PLAYLIST', 'ALBUM'],
    );
    assert.ok(releases.items[0].entity.providerId.startsWith('MPRE'));
    assert.ok(releases.items.every((item) => !item.track));
  });

  test("the home's chips become filters", () => {
    const labels = page.filters.options.map((option) => option.label);
    for (const label of ['Relax', 'Workout', 'Energize']) assert.ok(labels.includes(label), label);
    assert.ok(page.filters.options.every((option) => option.id.startsWith('ggN')));
  });

  test('item ids are unique on the page', () => {
    const ids = blocks(page).flatMap((block) => block.items.map((item) => item.id));
    assert.equal(new Set(ids).size, ids.length);
  });

  test('a continuation page maps the same way, and a playlist header opens the playlist itself', async () => {
    const next = await call('metadata.home', { cursor: 'fixture-continuation' });
    const similar = blocks(next).find((block) => block.header?.context === 'SIMILAR TO');
    assert.deepEqual(similar.header.target, { kind: 'PLAYLIST', providerId: 'PLfixtureplaylist' });
    assert.equal(next.filters ?? null, null);
    assert.equal(next.nextCursor, 'fixture-continuation-2');
  });
});

describe('home requests', () => {
  test("a signed-in session reads the account's home, checked once, not the anonymous one", async () => {
    const { call, calls } = offlinePlugin(routes, { secrets: SIGNED_IN });
    await call('metadata.home', {});
    await call('metadata.home', {});
    const homes = calls.filter((it) => it.endpoint === 'browse');
    assert.equal(homes.length, 2);
    assert.ok(homes.every((it) => it.signed));
    assert.equal(calls.filter((it) => it.endpoint === 'account/account_menu').length, 1);
  });

  test('signed out, the anonymous home is read unsigned', async () => {
    const { call, calls } = offlinePlugin(routes);
    await call('metadata.home', {});
    assert.deepEqual(calls.map((it) => [it.endpoint, it.signed]), [['browse', false]]);
  });

  test('an account YouTube no longer knows is expired, not served a signed-out home', async () => {
    const { call, plugin } = offlinePlugin([[(it) => it.endpoint === 'account/account_menu', { actions: [] }], ...routes], { secrets: SIGNED_IN });
    await assert.rejects(call('metadata.home', {}), { code: 'SIGN_IN_EXPIRED' });
    assert.equal(JSON.parse(plugin.secrets.get('session')).expired, true);
  });

  test('a filter selects the first page only, continuations already carry it', async () => {
    const { call, calls } = offlinePlugin(routes);
    await call('metadata.home', { filterId: 'relax' });
    await call('metadata.home', { filterId: 'relax', cursor: 'fixture-continuation' });
    assert.deepEqual(calls[0].body, { context: calls[0].body.context, browseId: 'FEmusic_home', params: 'relax' });
    assert.deepEqual(calls[1].body, { context: calls[1].body.context, continuation: 'fixture-continuation' });
  });
});
