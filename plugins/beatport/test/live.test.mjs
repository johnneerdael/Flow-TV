// Smoke tests against live Beatport, signed in with the API docs' browsing flow; run with LIVE=1 and
// the owner's credentials (see scripts/browse-session.mjs). They check shapes and the behaviours
// that matter (every page type has blocks, lists page on), not exact data.
import assert from 'node:assert/strict';
import { before, describe, test } from 'node:test';
import { loadPlugin } from '@milkbeat/mbplugin/harness';
import { browseSession } from '../scripts/browse-session.mjs';
import { IDS } from '../scripts/scenario.mjs';
import { LIVE, PLUGIN_DIR, block, collections, header } from './helpers.mjs';

const ref = (kind, providerId) => ({ kind, providerId });

describe('live Beatport', { skip: !LIVE && 'set LIVE=1 to run against Beatport' }, () => {
  let call;
  before(async () => {
    const session = await browseSession();
    ({ call } = loadPlugin(PLUGIN_DIR, { secrets: { session: JSON.stringify(session) } }));
  });

  test('home: the Top 100s, new charts, genres and the listener’s own shelves', async () => {
    const home = await call('metadata.home', {});
    const ids = collections(home).map((shelf) => shelf.id);
    for (const id of ['top-tracks', 'top-releases', 'new-charts', 'genres']) assert.ok(ids.includes(id), `${id} in ${ids}`);
    assert.equal(block(home, 'top-tracks').items.length, 20);
    assert.ok(block(home, 'genres').items.length >= 40);
  });

  test('search: several kinds, and a filter that pages on without repeating', async () => {
    const summary = await call('metadata.search', { query: 'solomun' });
    assert.ok(collections(summary).length >= 3);
    const first = await call('metadata.search', { query: 'solomun', filterId: 'tracks' });
    assert.equal(first.blocks[0].items.length, 50);
    const second = await call('metadata.search', { query: 'solomun', filterId: 'tracks', cursor: first.nextCursor });
    const seen = new Set(first.blocks[0].items.map((item) => item.entity.providerId));
    assert.ok(second.blocks[0].items.some((item) => !seen.has(item.entity.providerId)));
    const playlists = await call('metadata.search', { query: 'shortlist', filterId: 'playlists' });
    assert.ok(playlists.blocks[0].items.length > 0);
  });

  test('a release: header, every track, more from the label', async () => {
    const page = await call('metadata.entity', { entity: ref('ALBUM', IDS.release) });
    assert.ok(header(page).details.length >= 3);
    assert.equal(block(page, 'tracks').items.length, 5);
    const all = await call('metadata.tracks', { entity: ref('ALBUM', IDS.release) });
    assert.equal(all.tracks.length, 5);
    assert.ok(all.tracks.every((track) => track.ids.beatport && track.ids.isrc && track.durationMs > 0));
  });

  test('an artist and a label: Featured shelves, and their lists page on', async () => {
    const artist = await call('metadata.entity', { entity: ref('ARTIST', IDS.artist) });
    assert.deepEqual(collections(artist).map((shelf) => shelf.id), ['latest-releases', 'charts', 'top-10']);
    const releases = await call('metadata.entity', { entity: ref('MIX', `artist:${IDS.artist}:releases`) });
    assert.equal(releases.nextCursor, '2');
    const more = await call('metadata.entity', { entity: ref('MIX', `artist:${IDS.artist}:releases`), cursor: '2' });
    assert.equal(more.blocks[0].items.length, 50);
    const label = await call('metadata.entity', { entity: ref('PROFILE', `label:${IDS.label}`) });
    assert.deepEqual(collections(label).map((shelf) => shelf.id), ['latest-releases', 'top-10']);
  });

  test('a genre: Featured with its Top 10s and curated playlists, which page to the end', async () => {
    const genre = await call('metadata.entity', { entity: ref('MIX', `genre:${IDS.genre}`) });
    assert.equal(block(genre, 'top-10').items.length, 10);
    assert.equal(block(genre, 'hype-top-10').items.length, 10);
    assert.ok(block(genre, 'playlists').items.length > 0);
    let cursor;
    let pages = 0;
    let total = 0;
    do {
      const page = await call('metadata.entity', { entity: ref('MIX', `genre:${IDS.genre}:playlists`), cursor });
      total += page.blocks.find((found) => found.type === 'collection').items.length;
      cursor = page.nextCursor;
      pages++;
    } while (cursor && pages < 10);
    assert.equal(cursor, undefined, 'the playlists end');
    assert.ok(total > 50);
    const top = await call('metadata.tracks', { entity: ref('MIX', `genre:${IDS.genre}`) });
    assert.equal(top.tracks.length, 100);
  });

  test('a long chart and a curated playlist play in full', async () => {
    const first = await call('metadata.tracks', { entity: ref('PLAYLIST', `chart:${IDS.longChart}`) });
    assert.equal(first.tracks.length, 100);
    const rest = await call('metadata.tracks', { entity: ref('PLAYLIST', `chart:${IDS.longChart}`), cursor: first.next });
    assert.ok(rest.tracks.length > 0);
    assert.equal(rest.next, undefined);
    const curated = await call('metadata.entity', { entity: ref('PLAYLIST', `curated:${IDS.curated}`) });
    assert.ok(block(curated, 'tracks').items.length > 10);
  });

  test('the library: the listener’s playlists and follows', async () => {
    const library = await call('metadata.library', {});
    assert.ok(collections(library).length >= 1);
    const playlists = await call('metadata.library', { section: 'playlists' });
    assert.ok(playlists.blocks.length <= 1);
    const account = await call('signIn.account', {});
    assert.equal(account.type, 'signedIn');
  });
});
