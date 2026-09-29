// Checks shared by the video-page tests.
import assert from 'node:assert/strict';
import { itemsOf } from './fixtures.mjs';

export function assertVideoItem(item, view = 'LANDSCAPE_CARD') {
  assert.equal(item.entity.kind, 'VIDEO');
  assert.equal(item.view, view);
  assert.ok(item.track, `${item.id} carries its track`);
  assert.deepEqual(item.track.ref, item.entity);
  assert.equal(item.track.title, item.title);
  assert.equal(item.track.hasVideo, true);
  assert.deepEqual(item.track.ids, { yt: item.entity.providerId });
  assert.match(item.track.artwork.url, /^https:\/\/i\.ytimg\.com\/vi\//);
  if (!item.live && !item.upcoming) {
    assert.ok(item.durationSeconds > 0, `${item.id} has a duration`);
    assert.equal(item.track.durationMs, item.durationSeconds * 1000);
  }
}

export function assertUniqueIds(page) {
  const ids = itemsOf(page).map((item) => item.id);
  assert.equal(new Set(ids).size, ids.length, 'item ids are unique on the page');
}
