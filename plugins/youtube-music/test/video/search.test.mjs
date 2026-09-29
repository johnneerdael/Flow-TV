// Video search and suggestions against recorded responses (RECORD=1 re-records them live).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { assertUniqueIds, assertVideoItem } from './assertions.mjs';
import { itemsOf, offlinePlugin } from './fixtures.mjs';

const QUERY = 'stephan bodzin cercle';

test('an unfiltered search mixes videos, channels and playlists and offers the TV filters', async () => {
  const plugin = offlinePlugin();
  const page = await plugin.call('video.search', { query: QUERY });
  assert.deepEqual(
    page.filters.options.map((option) => option.id),
    ['videos', 'channels', 'playlists', 'live'],
  );
  const results = page.blocks.find((block) => block.id === 'results');
  const kinds = new Set(results.items.map((item) => item.entity.kind));
  assert.deepEqual([...kinds].sort(), ['CHANNEL', 'PLAYLIST', 'VIDEO']);
  results.items.filter((item) => item.entity.kind === 'VIDEO').forEach((item) => assertVideoItem(item));

  const first = results.items[0];
  assert.equal(first.entity.providerId, 'xF_QkfZI1mM');
  assert.equal(first.subtitle, 'Cercle');
  assert.deepEqual(first.artists, [{ name: 'Cercle', entity: { kind: 'CHANNEL', providerId: 'UCPKT_csvP72boVX0XrMtagQ' } }]);
  assert.equal(first.details.length, 2, 'views and age');

  const channel = results.items.find((item) => item.entity.providerId === 'UCPKT_csvP72boVX0XrMtagQ');
  assert.equal(channel.view, 'ARTIST_PORTRAIT');
  assert.equal(channel.subtitle, '@Cercle');
  assert.match(channel.details[0], /subscribers/);

  const playlist = results.items.find((item) => item.entity.kind === 'PLAYLIST' && item.entity.providerId.startsWith('PL'));
  assert.match(playlist.details.at(-1), /videos/);
  assertUniqueIds(page);
  assert.ok(page.nextCursor);
});

test("a creator query keeps YouTube's shelves as their own titled blocks", async () => {
  const plugin = offlinePlugin();
  const page = await plugin.call('video.search', { query: 'lofi girl' });
  const shelf = page.blocks.find((block) => block.id.startsWith('shelf:'));
  assert.match(shelf.header.title, /^Latest from /);
  assert.ok(shelf.items.length > 0);
  shelf.items.forEach((item) => assertVideoItem(item));
  assert.ok(shelf.items.every((item) => item.id.startsWith(`${shelf.id}/`)));
  assertUniqueIds(page);
});

test('a cursor continues the same results block', async () => {
  const plugin = offlinePlugin();
  const first = await plugin.call('video.search', { query: QUERY });
  const next = await plugin.call('video.search', { query: QUERY, cursor: first.nextCursor });
  assert.equal(next.filters, undefined);
  const results = next.blocks.find((block) => block.id === 'results');
  assert.ok(results.items.length > 0);
  const firstIds = new Set(itemsOf(first).map((item) => item.id));
  assert.ok(results.items.some((item) => !firstIds.has(item.id)), 'the next page brings new results');
});

for (const [filterId, kind] of [
  ['videos', 'VIDEO'],
  ['channels', 'CHANNEL'],
  ['playlists', 'PLAYLIST'],
]) {
  test(`the ${filterId} filter lists only ${kind} items`, async () => {
    const plugin = offlinePlugin();
    const page = await plugin.call('video.search', { query: QUERY, filterId });
    const items = page.blocks.find((block) => block.id === 'results').items;
    assert.ok(items.length >= 10);
    assert.ok(items.every((item) => item.entity.kind === kind));
    if (kind === 'VIDEO') items.forEach((item) => assertVideoItem(item));
    assert.ok(page.nextCursor);
  });
}

test('the live filter lists streams that are live now, without a duration', async () => {
  const plugin = offlinePlugin();
  const page = await plugin.call('video.search', { query: 'lofi hip hop radio', filterId: 'live' });
  const items = page.blocks.find((block) => block.id === 'results').items;
  assert.ok(items.length > 0);
  for (const item of items) {
    assertVideoItem(item);
    assert.equal(item.live, true);
    assert.equal(item.durationSeconds, undefined);
    if (item.details.length > 0) assert.match(item.details[0], /watching/);
  }
});

test('an unknown filter is refused', async () => {
  const plugin = offlinePlugin();
  await assert.rejects(plugin.call('video.search', { query: QUERY, filterId: 'shorts' }), { code: 'NOT_FOUND' });
});

test('suggestions come from the typeahead, each once', async () => {
  const plugin = offlinePlugin();
  const { queries } = await plugin.call('video.suggest', { query: 'stephan bodz' });
  assert.ok(queries.includes('stephan bodzin cercle'));
  assert.equal(new Set(queries).size, queries.length);
  assert.deepEqual(await plugin.call('video.suggest', { query: '  ' }), { queries: [] });
});
