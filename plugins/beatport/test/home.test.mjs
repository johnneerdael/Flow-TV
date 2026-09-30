// The home page from recorded fixtures: every shelf in order, each opening its full list, from one
// bounded round of requests; a part Beatport refuses is left out, a lapsed sign-in fails the page.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { API, block, collections, offlinePlugin, pathIs, recorded } from './helpers.mjs';

describe('home', async () => {
  const { call, calls } = offlinePlugin();
  const page = await call('metadata.home', {});

  test('shelves come in the web store’s order, each with its show-all', () => {
    assert.deepEqual(
      collections(page).map((shelf) => [shelf.id, shelf.header?.title, shelf.showAll ? `${shelf.showAll.kind}:${shelf.showAll.providerId}` : null]),
      [
        ['for-you', 'For You', 'MIX:for-you'],
        ['top-tracks', 'Beatport Top 100', 'PLAYLIST:top:all'],
        ['top-releases', 'Top 100 Releases', 'MIX:top:releases'],
        ['new-charts', 'New Charts', 'MIX:charts:new'],
        ['genres', 'Genres', null],
        ['my-playlists', 'Your Playlists', 'MIX:my:playlists'],
        ['followed-artists', 'Followed Artists', 'MIX:my:artists'],
      ],
    );
  });

  test('one request per shelf, all sent as the account', () => {
    assert.equal(calls.length, 7);
    assert.ok(calls.every((call) => call.authorization === 'Bearer fixture-token'));
    assert.equal(new Set(calls.map((call) => call.path)).size, 7);
  });

  test('the Top 100 is numbered and playable; releases, charts and genres are cards', () => {
    const top = block(page, 'top-tracks');
    assert.equal(top.layout, 'MULTI_COLUMN_LIST');
    assert.deepEqual(top.items.map((item) => item.ordinal), top.items.map((_, index) => index + 1));
    assert.ok(top.items.every((item) => item.track?.ids.beatport === item.entity.providerId));
    const expected = recorded(`${API}/v4/catalog/tracks/top/100/?per_page=20`).results.map((track) => String(track.id));
    assert.deepEqual(top.items.map((item) => item.entity.providerId), expected);

    assert.ok(block(page, 'top-releases').items.every((item) => item.entity.kind === 'ALBUM' && item.view === 'COVER_CARD'));
    assert.ok(block(page, 'new-charts').items.every((item) => item.entity.kind === 'PLAYLIST' && item.entity.providerId.startsWith('chart:')));
    const genres = block(page, 'genres').items;
    assert.equal(genres.length, 47);
    assert.ok(genres.every((item) => item.entity.kind === 'MIX' && /^genre:\d+$/.test(item.entity.providerId)));
  });

  test('the picks are mapped from their own schema', () => {
    const pick = recorded(`${API}/catalog/v1/recommendations/user/`)[0];
    const item = block(page, 'for-you').items[0];
    assert.equal(item.entity.providerId, String(pick.track_id));
    assert.equal(item.title, `${pick.track_name} (${pick.mix_name})`);
    assert.equal(item.track.ids.isrc, pick.isrc);
    assert.equal(item.track.artwork.url, pick.release.image_url.replace('{w}x{h}', '1400x1400'));
  });

  test('the listener’s playlists and followed artists open their own pages', () => {
    assert.deepEqual(block(page, 'my-playlists').items.map((item) => item.entity.providerId), ['mine:900001', 'mine:900002', 'mine:900003']);
    assert.ok(block(page, 'followed-artists').items.every((item) => item.entity.kind === 'ARTIST' && item.view === 'ARTIST_PORTRAIT'));
  });

  test('picks refused with this token leave the rest of the home in place', async () => {
    const refused = offlinePlugin({ routes: [[pathIs('/catalog/v1/recommendations/user/'), { status: 401, body: { message: 'Invalid or expired token' } }]] });
    const home = await refused.call('metadata.home', {});
    assert.equal(collections(home)[0].id, 'top-tracks');
    assert.equal(collections(home).length, 6);
  });

  test('a lapsed sign-in fails the whole page, and marks the session expired', async () => {
    const expired = offlinePlugin({ routes: [[pathIs('/v4/catalog/genres/'), { status: 401, body: { detail: 'expired' } }]] });
    await assert.rejects(expired.call('metadata.home', {}), { code: 'SIGN_IN_EXPIRED' });
    assert.deepEqual(await expired.call('signIn.account', {}), { type: 'expired' });
  });
});
