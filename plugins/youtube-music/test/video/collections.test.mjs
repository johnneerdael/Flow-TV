// Channel pages with their tabs, video playlists and mixes, and their tracks, against recorded responses.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { assertUniqueIds, assertVideoItem } from './assertions.mjs';
import { offlinePlugin } from './fixtures.mjs';

const CERCLE = { kind: 'CHANNEL', providerId: 'UCPKT_csvP72boVX0XrMtagQ' };
const SHOWS = { kind: 'PLAYLIST', providerId: 'PLDitloyBcHOm_Q06fztzSfLp19AJYX141' };
const MIX = { kind: 'PLAYLIST', providerId: 'RDxF_QkfZI1mM' };

const collectionOf = (page, id) => page.blocks.find((block) => block.type === 'collection' && block.id === id);

test('a channel opens on its Videos tab under a portrait header, with the TV tabs as filters', async () => {
  const log = [];
  const plugin = offlinePlugin(log);
  const page = await plugin.call('video.entity', { entity: CERCLE });
  assert.equal(log.length, 1, 'one browse');
  const [header] = page.blocks;
  assert.equal(header.type, 'header');
  assert.equal(header.style, 'PORTRAIT');
  assert.equal(header.title, 'Cercle');
  assert.deepEqual(header.entity, CERCLE);
  assert.deepEqual(header.tracks, CERCLE);
  assert.equal(header.details[0], '@Cercle');
  assert.ok(header.details.some((line) => /subscribers/.test(line)));
  assert.ok(header.description.length > 100);
  assert.deepEqual(
    page.filters.options.map((option) => option.id),
    ['videos', 'live', 'playlists', 'about'],
  );
  const videos = collectionOf(page, 'tab:videos');
  assert.equal(videos.items.length, 30);
  videos.items.forEach((item) => assertVideoItem(item));
  assert.ok(videos.items.every((item) => item.artists[0].entity.providerId === CERCLE.providerId));
  assertUniqueIds(page);
  assert.ok(page.nextCursor);
});

test('a channel tab pages into the same block, keeping the channel on anonymous items', async () => {
  const plugin = offlinePlugin();
  let page = await plugin.call('video.entity', { entity: CERCLE });
  const seen = new Set(collectionOf(page, 'tab:videos').items.map((item) => item.id));
  for (let pages = 0; pages < 2; pages++) {
    page = await plugin.call('video.entity', { entity: CERCLE, filterId: 'videos', cursor: page.nextCursor });
    assert.equal(page.blocks.length, 1, 'a continuation carries no header');
    const items = collectionOf(page, 'tab:videos').items;
    assert.equal(items.length, 30);
    items.forEach((item) => assertVideoItem(item));
    assert.ok(items.every((item) => item.subtitle && !seen.has(item.id)));
    items.forEach((item) => seen.add(item.id));
  }
  assert.ok(page.nextCursor);
});

test('the Live and Playlists tabs list their own items', async () => {
  const plugin = offlinePlugin();
  const live = await plugin.call('video.entity', { entity: CERCLE, filterId: 'live' });
  const streams = collectionOf(live, 'tab:live').items;
  assert.ok(streams.length > 0);
  streams.forEach((item) => assertVideoItem(item));
  assert.ok(streams.every((item) => item.details.some((line) => /Streamed/.test(line))));

  const playlists = await plugin.call('video.entity', { entity: CERCLE, filterId: 'playlists' });
  const items = collectionOf(playlists, 'tab:playlists').items;
  assert.ok(items.length >= 10);
  assert.ok(items.every((item) => item.entity.kind === 'PLAYLIST' && item.subtitle === 'Cercle'));
  assert.ok(items.some((item) => item.entity.providerId === SHOWS.providerId && item.details.includes('164 videos')));
});

test('the About tab completes the header from the About panel', async () => {
  const log = [];
  const plugin = offlinePlugin(log);
  const page = await plugin.call('video.entity', { entity: CERCLE, filterId: 'about' });
  assert.equal(page.blocks.length, 1);
  assert.ok(page.blocks[0].details.some((line) => /^Joined /.test(line)));
  assert.ok(page.blocks[0].details.some((line) => / views$/.test(line)));
  assert.equal(log.length, 2, 'the landing browse and the About panel');
});

test("a channel's tracks are its Videos tab, paged", async () => {
  const plugin = offlinePlugin();
  const first = await plugin.call('video.tracks', { entity: CERCLE });
  assert.equal(first.tracks.length, 30);
  assert.deepEqual(first.source, CERCLE);
  assert.ok(first.tracks.every((track) => track.hasVideo && track.ids.yt === track.ref.providerId && track.artists[0].entity.providerId === CERCLE.providerId));
  const second = await plugin.call('video.tracks', { entity: CERCLE, cursor: first.next });
  assert.equal(second.tracks.length, 30);
  assert.ok(second.tracks.every((track) => track.artists[0].entity.providerId === CERCLE.providerId));
});

test('a video playlist reads to the end, numbered across pages', async () => {
  const plugin = offlinePlugin();
  let page = await plugin.call('video.entity', { entity: SHOWS });
  const [header] = page.blocks;
  assert.equal(header.style, 'COVER');
  assert.equal(header.title, 'Cercle Shows');
  assert.deepEqual(header.tracks, SHOWS);
  assert.equal(header.attribution.name, 'Cercle');
  assert.deepEqual(header.attribution.entity, CERCLE);
  assert.ok(header.details.includes('164 videos'));
  const rows = [...collectionOf(page, 'tracks').items];
  assert.equal(collectionOf(page, 'tracks').layout, 'TRACK_TABLE');
  while (page.nextCursor) {
    page = await plugin.call('video.entity', { entity: SHOWS, cursor: page.nextCursor });
    rows.push(...collectionOf(page, 'tracks').items);
  }
  assert.ok(rows.length > 150, `${rows.length} of 164 (unavailable videos are left out)`);
  for (const row of rows) assertVideoItem(row, 'TRACK_ROW');
  assert.deepEqual(
    rows.map((row) => row.ordinal),
    rows.map((_, index) => index + 1),
  );
  assert.equal(new Set(rows.map((row) => row.id)).size, rows.length);
});

test("a video playlist's tracks page to the end", async () => {
  const plugin = offlinePlugin();
  let list = await plugin.call('video.tracks', { entity: SHOWS });
  const tracks = [...list.tracks];
  while (list.next) {
    list = await plugin.call('video.tracks', { entity: SHOWS, cursor: list.next });
    tracks.push(...list.tracks);
  }
  assert.ok(tracks.length > 150);
  assert.deepEqual(list.source, SHOWS);
});

test('a mix is read from its watch page and continues without repeats', async () => {
  const plugin = offlinePlugin();
  const page = await plugin.call('video.entity', { entity: MIX });
  assert.match(page.blocks[0].title, /^Mix - /);
  assert.ok(collectionOf(page, 'tracks').items.length >= 20);

  let list = await plugin.call('video.tracks', { entity: MIX });
  const ids = list.tracks.map((track) => track.ref.providerId);
  list = await plugin.call('video.tracks', { entity: MIX, cursor: list.next });
  assert.ok(list.tracks.length > 0);
  ids.push(...list.tracks.map((track) => track.ref.providerId));
  assert.equal(new Set(ids).size, ids.length);
});

test('a missing playlist is NOT_FOUND, and other kinds are unsupported', async () => {
  const plugin = offlinePlugin();
  await assert.rejects(
    plugin.call('video.entity', { entity: { kind: 'PLAYLIST', providerId: 'PLdoesnotexist000000000000000000' } }),
    { code: 'NOT_FOUND' },
  );
  await assert.rejects(plugin.call('video.entity', { entity: { kind: 'VIDEO', providerId: 'xF_QkfZI1mM' } }), {
    code: 'UNSUPPORTED',
  });
  await assert.rejects(plugin.call('video.tracks', { entity: { kind: 'VIDEO', providerId: 'xF_QkfZI1mM' } }), {
    code: 'UNSUPPORTED',
  });
});
