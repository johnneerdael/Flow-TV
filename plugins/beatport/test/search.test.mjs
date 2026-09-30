// Search from recorded fixtures: the mixed results in Beatport's order with charts and curated
// playlists from their own searches, every kind's filter, and a filtered search paging on.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { API, block, collections, offlinePlugin } from './helpers.mjs';

describe('search', async () => {
  const { call, calls } = offlinePlugin();
  const summary = await call('metadata.search', { query: 'solomun' });
  const summaryCalls = [...calls];

  test('kinds follow Beatport’s ranking, then the ones it did not rank', () => {
    assert.deepEqual(
      collections(summary).map((kind) => kind.id),
      ['search/artists', 'search/tracks', 'search/releases', 'search/labels', 'search/charts'],
    );
    assert.equal(block(summary, 'search/tracks').layout, 'TRACK_TABLE');
    assert.equal(block(summary, 'search/artists').defaultItemView, 'ARTIST_PORTRAIT');
  });

  test('every kind opens its filter, and the filters are offered in a fixed order', () => {
    assert.ok(collections(summary).every((kind) => kind.showAllFilterId === kind.id.split('/')[1]));
    assert.deepEqual(
      summary.filters.options.map((option) => option.id),
      ['tracks', 'releases', 'artists', 'labels', 'charts', 'playlists'],
    );
  });

  test('charts and curated playlists are searched on their own, the playlists by every genre', () => {
    const typed = summaryCalls.filter((call) => call.path === '/v4/catalog/search/' && call.query.type).map((call) => call.query.type);
    assert.deepEqual(typed.sort(), ['charts', 'playlists']);
    const playlists = summaryCalls.find((call) => call.query.type === 'playlists');
    assert.equal(playlists.query.genre_id.split(',').length, 47);
  });

  test('labels are profiles with a prefixed id; charts are playlists', () => {
    assert.match(block(summary, 'search/labels').items[0].entity.providerId, /^label:\d+$/);
    assert.equal(block(summary, 'search/labels').items[0].entity.kind, 'PROFILE');
    assert.match(block(summary, 'search/charts').items[0].entity.providerId, /^chart:\d+$/);
  });

  test('a filter pages on by page number, extending the same table', async () => {
    const first = await call('metadata.search', { query: 'solomun', filterId: 'tracks' });
    assert.equal(first.nextCursor, '2');
    assert.equal(first.blocks[0].id, 'search/tracks');
    const second = await call('metadata.search', { query: 'solomun', filterId: 'tracks', cursor: first.nextCursor });
    assert.equal(second.blocks[0].id, 'search/tracks');
    assert.equal(second.nextCursor, '3');
    const firstIds = new Set(first.blocks[0].items.map((item) => item.entity.providerId));
    assert.ok(second.blocks[0].items.every((item) => !firstIds.has(item.entity.providerId)));
    const asked = calls.filter((call) => call.query.type === 'tracks').map((call) => call.query.page);
    assert.deepEqual(asked, ['1', '2']);
  });

  test('the playlists filter finds Beatport’s curated playlists, which open under curation/', async () => {
    const page = await call('metadata.search', { query: 'shortlist', filterId: 'playlists' });
    const items = page.blocks[0].items;
    assert.ok(items.length > 0);
    assert.ok(items.every((item) => item.entity.kind === 'PLAYLIST' && item.entity.providerId.startsWith('curated:')));
  });

  test('an unknown filter or a cursor on the mixed results is refused', async () => {
    await assert.rejects(call('metadata.search', { query: 'solomun', filterId: 'videos' }), { code: 'NOT_FOUND' });
    await assert.rejects(call('metadata.search', { query: 'solomun', cursor: '2' }), { code: 'NOT_FOUND' });
    await assert.rejects(call('metadata.search', { query: 'solomun', filterId: 'tracks', cursor: 'abc' }), { code: 'NOT_FOUND' });
  });

  test('without a session nothing is asked of Beatport', async () => {
    const anonymous = offlinePlugin({ secrets: {} });
    await assert.rejects(anonymous.call('metadata.search', { query: 'solomun' }), { code: 'SIGN_IN_REQUIRED' });
    assert.equal(anonymous.calls.length, 0);
  });

  test('the request carries the query as typed', () => {
    const mixed = summaryCalls.find((call) => call.path === '/v4/catalog/search/' && !call.query.type);
    assert.equal(mixed.query.q, 'solomun');
    assert.ok(mixed.url.startsWith(`${API}/v4/catalog/search/?`));
  });
});
