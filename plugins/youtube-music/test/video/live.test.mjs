// Smoke tests against YouTube itself: `LIVE=1 node --test test/video/live.test.mjs`.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { assertUniqueIds, assertVideoItem } from './assertions.mjs';
import { itemsOf, livePlugin } from './fixtures.mjs';

const skip = process.env.LIVE !== '1' && 'set LIVE=1 to reach YouTube';
const CERCLE = { kind: 'CHANNEL', providerId: 'UCPKT_csvP72boVX0XrMtagQ' };

test('search with every filter, and its second page', { skip }, async () => {
  const plugin = livePlugin();
  const all = await plugin.call('video.search', { query: 'stephan bodzin cercle' });
  assertUniqueIds(all);
  for (const option of all.filters.options) {
    const page = await plugin.call('video.search', { query: option.id === 'live' ? 'lofi hip hop radio' : 'stephan bodzin cercle', filterId: option.id });
    const items = itemsOf(page);
    assert.ok(items.length > 0, `${option.id} has results`);
    items.filter((item) => item.entity.kind === 'VIDEO').forEach((item) => assertVideoItem(item));
    if (option.id === 'live') assert.ok(items.every((item) => item.live));
    const next = await plugin.call('video.search', { query: 'stephan bodzin cercle', cursor: page.nextCursor });
    assert.ok(itemsOf(next).length > 0, `${option.id} pages`);
  }
  assert.ok((await plugin.call('video.suggest', { query: 'stephan bodz' })).queries.length > 0);
});

test('a channel with its tabs and paging, and its tracks', { skip }, async () => {
  const plugin = livePlugin();
  const page = await plugin.call('video.entity', { entity: CERCLE });
  for (const option of page.filters.options) {
    const tab = await plugin.call('video.entity', { entity: CERCLE, filterId: option.id });
    assert.equal(tab.blocks[0].type, 'header');
    if (option.id !== 'about') assert.ok(itemsOf(tab).length > 0, `${option.id} has items`);
  }
  const second = await plugin.call('video.entity', { entity: CERCLE, filterId: 'videos', cursor: page.nextCursor });
  assert.equal(itemsOf(second).length, 30);
  assert.ok((await plugin.call('video.tracks', { entity: CERCLE })).tracks.length > 0);
});

test('a video playlist of more than a hundred videos, to the end', { skip }, async () => {
  const plugin = livePlugin();
  const entity = { kind: 'PLAYLIST', providerId: 'PLDitloyBcHOm_Q06fztzSfLp19AJYX141' };
  let list = await plugin.call('video.tracks', { entity });
  let count = list.tracks.length;
  while (list.next) {
    list = await plugin.call('video.tracks', { entity, cursor: list.next });
    count += list.tracks.length;
  }
  assert.ok(count > 100, `${count} tracks`);
});

test('related videos, comments in both orders with replies, and a live chat', { skip }, async () => {
  const plugin = livePlugin();
  const video = { kind: 'VIDEO', providerId: 'xF_QkfZI1mM' };
  const related = await plugin.call('video.related', { entity: video });
  assert.ok(itemsOf(related).length > 5);
  const top = await plugin.call('video.comments', { entity: video });
  const newest = await plugin.call('video.comments', { entity: video, sortId: 'newest' });
  assert.ok(top.comments.length > 0 && newest.comments.length > 0);
  const parent = top.comments.find((comment) => comment.repliesCursor);
  assert.ok((await plugin.call('video.comments', { entity: video, cursor: parent.repliesCursor })).comments.length > 0);

  const live = itemsOf(await plugin.call('video.search', { query: 'lofi hip hop radio', filterId: 'live' })).find((item) => item.live);
  const batch = await plugin.call('video.liveChat', { entity: live.entity });
  assert.ok(batch.next && batch.pollAfterMs >= 1000);
});
