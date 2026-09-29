// Related videos, comments and live chat against recorded responses.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { assertUniqueIds, assertVideoItem } from './assertions.mjs';
import { offlinePlugin } from './fixtures.mjs';

const VIDEO = { kind: 'VIDEO', providerId: 'xF_QkfZI1mM' };

test("related videos come under the video's own details", async () => {
  const plugin = offlinePlugin();
  const page = await plugin.call('video.related', { entity: VIDEO });
  const [header, related] = page.blocks;
  assert.equal(header.type, 'header');
  assert.deepEqual(header.entity, VIDEO);
  assert.equal(header.title, 'Stephan Bodzin live for Cercle at Piz Gloria, Switzerland');
  assert.equal(header.attribution.name, 'Cercle');
  assert.match(header.details[0], /views/);
  assert.ok(header.description.length > 50);
  assert.equal(related.id, 'related');
  assert.ok(related.items.length >= 10);
  related.items.forEach((item) => assertVideoItem(item));
  assert.ok(related.items.every((item) => item.entity.providerId !== VIDEO.providerId));
  assertUniqueIds(page);

  const more = await plugin.call('video.related', { entity: VIDEO, cursor: page.nextCursor });
  assert.equal(more.blocks.length, 1);
  assert.equal(more.blocks[0].id, 'related');
  assert.ok(more.blocks[0].items.length > 0);
});

test('comments come with their sort orders, total and a cursor for more', async () => {
  const plugin = offlinePlugin();
  const page = await plugin.call('video.comments', { entity: VIDEO });
  assert.deepEqual(
    page.sorts.map((sort) => sort.id),
    ['top', 'newest'],
  );
  assert.match(page.totalLabel, /Comments/);
  assert.equal(page.comments.length, 20);
  for (const comment of page.comments) {
    assert.ok(comment.id && comment.author && comment.text && comment.publishedLabel);
    assert.ok(comment.authorAvatar.url.startsWith('https://'));
  }
  assert.equal(new Set(page.comments.map((comment) => comment.id)).size, page.comments.length);
  assert.ok(page.next);

  const more = await plugin.call('video.comments', { entity: VIDEO, cursor: page.next });
  assert.ok(more.comments.length > 0);
});

test("a comment's replies continue from its cursor", async () => {
  const plugin = offlinePlugin();
  const page = await plugin.call('video.comments', { entity: VIDEO });
  const parent = page.comments.find((comment) => comment.repliesCursor);
  assert.ok(parent.replyCount > 0);
  const replies = await plugin.call('video.comments', { entity: VIDEO, cursor: parent.repliesCursor });
  assert.ok(replies.comments.length > 0);
  assert.ok(replies.comments.every((reply) => reply.id.startsWith(`${parent.id}.`)));
});

test('newest first is one more request, and none more once the orders are known', async () => {
  const log = [];
  const plugin = offlinePlugin(log);
  const newest = await plugin.call('video.comments', { entity: VIDEO, sortId: 'newest' });
  assert.equal(log.length, 3, 'watch page, comment section, newest order');
  assert.ok(newest.comments.length > 0);
  await plugin.call('video.comments', { entity: VIDEO, sortId: 'top' });
  assert.equal(log.length, 4, 'the known order is fetched directly');
});

test('a live stream chat arrives in batches with the wait YouTube asks for', async () => {
  const plugin = offlinePlugin();
  const search = await plugin.call('video.search', { query: 'lofi hip hop radio', filterId: 'live' });
  const live = search.blocks[0].items[0];
  const batch = await plugin.call('video.liveChat', { entity: live.entity });
  assert.ok(batch.messages.length > 0);
  for (const message of batch.messages) assert.ok(message.id && message.author && typeof message.text === 'string');
  assert.ok(batch.next);
  assert.ok(batch.pollAfterMs >= 1000 && batch.pollAfterMs <= 30000);
  const next = await plugin.call('video.liveChat', { entity: live.entity, cursor: batch.next });
  assert.ok(Array.isArray(next.messages));
});

test('a video without live chat is UNAVAILABLE, and a non-video is unsupported', async () => {
  const plugin = offlinePlugin();
  await assert.rejects(plugin.call('video.liveChat', { entity: VIDEO }), { code: 'UNAVAILABLE' });
  await assert.rejects(plugin.call('video.related', { entity: { kind: 'CHANNEL', providerId: 'UCPKT_csvP72boVX0XrMtagQ' } }), {
    code: 'UNSUPPORTED',
  });
});
