// Entity pages from recorded fixtures: releases, artists, labels, genres (with their tabs and
// Featured modules), charts, curated playlists and the Top 100s.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { IDS } from '../scripts/scenario.mjs';
import { API, block, collections, header, offlinePlugin, pathIs, recorded } from './helpers.mjs';

const ref = (kind, providerId) => ({ kind, providerId });
const refText = (entity) => (entity ? `${entity.kind}:${entity.providerId}` : null);

describe('release', async () => {
  const { call } = offlinePlugin();
  const page = await call('metadata.entity', { entity: ref('ALBUM', IDS.release) });
  const release = recorded(`${API}/v4/catalog/releases/${IDS.release}/`);

  test('the header credits every artist and lists date, label, catalogue number and tempo', () => {
    const top = header(page);
    assert.equal(top.style, 'COVER');
    assert.equal(top.title, release.name);
    assert.equal(top.attribution.name, release.artists.map((artist) => artist.name).join(', '));
    assert.deepEqual(top.attribution.entity, ref('ARTIST', String(release.artists[0].id)));
    assert.deepEqual(top.details.slice(0, 3), [release.new_release_date, release.label.name, release.catalog_number]);
    assert.match(top.details[3], /BPM$/);
    assert.deepEqual(top.tracks, ref('ALBUM', IDS.release));
    assert.match(top.artwork.url, /\/image_size\/1400x1400\//);
  });

  test('its tracks are numbered, then more from the label links the label', () => {
    const tracks = block(page, 'tracks');
    assert.equal(tracks.layout, 'TRACK_TABLE');
    assert.deepEqual(tracks.items.map((item) => item.ordinal), tracks.items.map((_, index) => index + 1));
    const more = block(page, 'more-from-label');
    assert.equal(more.header.context, release.label.name);
    assert.deepEqual(more.header.target, ref('PROFILE', `label:${release.label.id}`));
    assert.equal(refText(more.showAll), `MIX:label:${release.label.id}:releases`);
    assert.ok(more.items.every((item) => item.entity.providerId !== IDS.release));
  });
});

describe('artist', async () => {
  const { call } = offlinePlugin();
  const page = await call('metadata.entity', { entity: ref('ARTIST', IDS.artist) });

  test('Featured: portrait with the bio, latest releases, the DJ profile’s charts, the top ten', () => {
    const artist = recorded(`${API}/v4/catalog/artists/${IDS.artist}/`);
    assert.equal(header(page).style, 'PORTRAIT');
    assert.equal(header(page).description, artist.bio?.trim() || undefined);
    assert.deepEqual(header(page).tracks, ref('PLAYLIST', `top:artist:${IDS.artist}`));
    assert.deepEqual(
      collections(page).map((shelf) => [shelf.id, refText(shelf.showAll)]),
      [
        ['latest-releases', `MIX:artist:${IDS.artist}:releases`],
        ['charts', `MIX:artist:${IDS.artist}:charts`],
        ['top-10', `PLAYLIST:top:artist:${IDS.artist}`],
      ],
    );
    assert.deepEqual(page.filters.options.map((option) => option.id), ['featured', 'tracks', 'releases', 'charts']);
  });

  test('the header says the listener follows the artist, and not the label', async () => {
    assert.deepEqual(header(page).details, ['Following on Beatport']);
    const label = await call('metadata.entity', { entity: ref('PROFILE', `label:${IDS.label}`) });
    assert.deepEqual(header(label).details, []);
  });

  test('an unknown follow state leaves the header as it is', async () => {
    const refused = offlinePlugin({ routes: [[pathIs('/v4/my/beatport/'), { status: 403, body: {} }]] });
    const artist = await refused.call('metadata.entity', { entity: ref('ARTIST', IDS.artist) });
    assert.deepEqual(header(artist).details, []);
  });

  test('the Charts tab keeps the header and tabs over a table of charts', async () => {
    const charts = await call('metadata.entity', { entity: ref('ARTIST', IDS.artist), filterId: 'charts' });
    assert.equal(header(charts).title, header(page).title);
    assert.ok(charts.filters);
    assert.equal(block(charts, 'tab/charts').layout, 'TRACK_TABLE');
  });

  test('releases opened on their own name the artist and page on', async () => {
    const list = await call('metadata.entity', { entity: ref('MIX', `artist:${IDS.artist}:releases`) });
    assert.equal(header(list), undefined);
    assert.equal(list.blocks[0].header.context, header(page).title);
    assert.equal(list.nextCursor, '2');
    const more = await call('metadata.entity', { entity: ref('MIX', `artist:${IDS.artist}:releases`), cursor: '2' });
    assert.equal(more.blocks[0].id, list.blocks[0].id);
    assert.equal(more.blocks[0].header, null);
  });
});

describe('label', async () => {
  const { call } = offlinePlugin();
  const page = await call('metadata.entity', { entity: ref('PROFILE', `label:${IDS.label}`) });

  test('its releases, then its top ten, with Play all its Top 100', () => {
    assert.equal(header(page).style, 'PORTRAIT');
    assert.deepEqual(header(page).tracks, ref('PLAYLIST', `top:label:${IDS.label}`));
    assert.deepEqual(collections(page).map((shelf) => shelf.id), ['latest-releases', 'top-10']);
  });

  test('the Tracks tab lists released tracks, newest first', async () => {
    const { call: tracksCall, calls } = offlinePlugin();
    const tab = await tracksCall('metadata.entity', { entity: ref('PROFILE', `label:${IDS.label}`), filterId: 'tracks' });
    assert.ok(block(tab, 'tab/tracks').items.every((item) => item.track));
    const asked = calls.find((call) => call.path === '/v4/catalog/tracks/');
    assert.equal(asked.query.label_id, IDS.label);
    assert.equal(asked.query.order_by, '-publish_date');
    assert.equal(asked.query.preorder, 'false');
  });
});

describe('genre', async () => {
  const { call, calls } = offlinePlugin();
  const page = await call('metadata.entity', { entity: ref('MIX', `genre:${IDS.genre}`) });

  test('Featured follows the web store: charts, releases, the Top 10s and the curated playlists', () => {
    const name = header(page).title;
    assert.deepEqual(
      collections(page).map((shelf) => [shelf.header.title, refText(shelf.showAll)]),
      [
        [`New ${name} Charts`, `MIX:genre:${IDS.genre}:charts`],
        [`Latest ${name} Releases`, `MIX:genre:${IDS.genre}:releases`],
        ['Beatport Top 10', `PLAYLIST:top:genre:${IDS.genre}`],
        ['Hype Top 10', `PLAYLIST:top:genre:${IDS.genre}:hype`],
        [`Top 10 ${name} Releases`, `MIX:top:genre:${IDS.genre}:releases`],
        ['Beatport Playlists', `MIX:genre:${IDS.genre}:playlists`],
      ],
    );
    assert.deepEqual(page.filters.options.map((option) => option.id), ['featured', 'tracks', 'releases', 'charts', 'playlists']);
    assert.equal(header(page).style, 'PORTRAIT');
  });

  test('its curated playlists are the ones filed under the genre', () => {
    const asked = calls.find((call) => call.path === '/v4/curation/playlists/');
    assert.equal(asked.query.genre_id, IDS.genre);
    assert.ok(block(page, 'playlists').items.every((item) => item.entity.providerId.startsWith('curated:')));
  });

  test('the Top 10 releases are the Top 100’s releases in chart order', () => {
    const top = recorded(`${API}/v4/catalog/genres/${IDS.genre}/top/100/?per_page=100`).results;
    const releases = [...new Set(top.map((track) => String(track.release.id)))].slice(0, 10);
    assert.deepEqual(block(page, 'top-releases').items.map((item) => item.entity.providerId), releases);
  });

  test('latest releases leave out pre-orders', () => {
    const asked = calls.find((call) => call.path === '/v4/catalog/releases/');
    assert.match(asked.query.publish_date, /^:\d{4}-\d{2}-\d{2}$/);
  });

  test('the curated playlists page on to the end', async () => {
    const list = await call('metadata.entity', { entity: ref('MIX', `genre:${IDS.genre}:playlists`) });
    assert.equal(list.nextCursor, '2');
    const last = await call('metadata.entity', { entity: ref('MIX', `genre:${IDS.genre}:playlists`), cursor: '2' });
    assert.equal(last.nextCursor, undefined);
    assert.ok(last.blocks[0].items.length > 0);
  });

  test('the web store’s editorial modules lay out the page when the account may read them', async () => {
    const releases = recorded(`${API}/v4/catalog/releases/top/100/?per_page=100`).results;
    const charts = recorded(`${API}/v4/catalog/charts/?order_by=-publish_date&page=1&per_page=20`).results;
    const entries = (type, list) => ({ results: list.map((item) => ({ item_type: { name: type }, item })) });
    const pages = {
      results: [
        { id: 757, name: 'Mobile', type: { name: 'genre' }, source_type: { name: 'mobileapp' } },
        { id: 435, name: 'Web', type: { name: 'genre' }, source_type: { name: 'sushi' } },
      ],
    };
    const modules = {
      results: [
        { id: 1, name: 'Melodic New Releases: Week 39', type: { name: 'releaseFeature' } },
        { id: 2, name: 'Melodic Banners: Week 39', type: { name: 'genreLargeSlideshow' } },
        { id: 3, name: 'Melodic Hype Picks: Week 39', type: { name: 'hypeFeature' } },
        { id: 4, name: 'Melodic Staff Picks: Week 39', type: { name: 'releaseFeature' } },
        { id: 5, name: 'Melodic Charts: Week 39', type: { name: 'chartFeature' } },
        { id: 6, name: 'Retired', enabled: false, type: { name: 'releaseFeature' } },
      ],
    };
    const banner = { results: [{ item_type: null, item: null, image: { uri: 'https://geo-media.beatport.com/image_size/920x642/banner.jpg' }, external_url: 'https://www.beatport.com/genre/indie-tech/112' }] };
    const items = { 1: entries('release', releases.slice(0, 3)), 2: banner, 3: entries('release', releases.slice(1, 3)), 4: entries('release', releases.slice(0, 2)), 5: entries('chart', charts.slice(0, 2)) };
    const curated = offlinePlugin({
      routes: [
        [(c) => c.path === '/v4/curation/pages/' && c.query.item_id === IDS.genre, { body: pages }],
        [(c) => c.path === '/v4/curation/page-modules/' && c.query.page_id === '435', { body: modules }],
        [(c) => /^\/v4\/curation\/page-modules\/\d+\/items\/$/.test(c.path), (c) => ({ body: items[c.path.split('/')[4]] })],
      ],
    });
    const featured = await curated.call('metadata.entity', { entity: ref('MIX', `genre:${IDS.genre}`) });
    const shelves = collections(featured);

    const [banners, newCharts, latest, hype, staff] = shelves;
    assert.equal(banners.header, null);
    assert.deepEqual([banners.items[0].view, banners.items[0].entity, banners.items[0].artwork.url], ['LANDSCAPE_CARD', ref('MIX', 'genre:112'), 'https://geo-media.beatport.com/image_size/920x642/banner.jpg']);
    assert.match(newCharts.header.title, /^New .* Charts$/);
    assert.deepEqual(newCharts.items.map((item) => item.entity.providerId), charts.slice(0, 2).map((chart) => `chart:${chart.id}`));
    assert.match(latest.header.title, /^Latest .* Releases$/);
    assert.deepEqual(latest.items.map((item) => item.entity.providerId), releases.slice(0, 3).map((release) => String(release.id)));
    assert.deepEqual([hype.header.title, staff.header.title], ['Hype Picks', 'Staff Picks']);
    assert.ok(!curated.calls.some((c) => c.path === '/v4/curation/page-modules/6/items/'));
  });

  test('a tab of the genre keeps its header and tabs', async () => {
    const charts = await call('metadata.entity', { entity: ref('MIX', `genre:${IDS.genre}`), filterId: 'charts' });
    assert.equal(header(charts).title, header(page).title);
    assert.equal(block(charts, 'tab/charts').items[0].entity.kind, 'PLAYLIST');
    await assert.rejects(call('metadata.entity', { entity: ref('MIX', `genre:${IDS.genre}`), filterId: 'videos' }), { code: 'NOT_FOUND' });
  });
});

describe('charts, playlists and Top 100s', async () => {
  const { call } = offlinePlugin();

  test('a chart: its curator, genres and count, then numbered tracks with Play all', async () => {
    const page = await call('metadata.entity', { entity: ref('PLAYLIST', `chart:${IDS.chart}`) });
    const chart = recorded(`${API}/v4/catalog/charts/${IDS.chart}/`);
    assert.equal(header(page).title, chart.name);
    assert.equal(header(page).attribution.name, chart.person.owner_name);
    assert.deepEqual(header(page).tracks, ref('PLAYLIST', `chart:${IDS.chart}`));
    assert.equal(block(page, 'tracks').items[0].ordinal, 1);
  });

  test('a long chart numbers on across pages', async () => {
    const first = await call('metadata.entity', { entity: ref('PLAYLIST', `chart:${IDS.longChart}`) });
    assert.equal(first.nextCursor, '2');
    const second = await call('metadata.entity', { entity: ref('PLAYLIST', `chart:${IDS.longChart}`), cursor: '2' });
    assert.equal(header(second), undefined);
    assert.equal(second.blocks[0].id, 'tracks');
    assert.equal(second.blocks[0].items[0].ordinal, 101);
  });

  test('a curated playlist shows its first release’s cover, its tempo and length', async () => {
    const page = await call('metadata.entity', { entity: ref('PLAYLIST', `curated:${IDS.curated}`) });
    const top = header(page);
    assert.match(top.artwork.url, /\/image_size\/1400x1400\//);
    assert.ok(top.details.some((line) => /BPM$/.test(line)));
    assert.ok(top.details.some((line) => /min$/.test(line)));
    assert.ok(block(page, 'tracks').items.every((item) => item.track));
  });

  test('a genre’s Hype Top 100 names the genre under its title', async () => {
    const page = await call('metadata.entity', { entity: ref('PLAYLIST', `top:genre:${IDS.genre}:hype`) });
    assert.equal(header(page).title, 'Hype Top 100');
    const genre = recorded(`${API}/v4/catalog/genres/?per_page=100`).results.find((found) => String(found.id) === IDS.genre);
    assert.equal(header(page).details[0], genre.name);
    assert.equal(page.nextCursor, undefined);
  });

  test('refs this plugin never issued are not found', async () => {
    for (const entity of [ref('PLAYLIST', 'chart:abc'), ref('MIX', 'genre:90:videos'), ref('PROFILE', '1328'), ref('PLAYLIST', 'top:genre:90:releases')]) {
      await assert.rejects(call('metadata.entity', { entity }), { code: 'NOT_FOUND' }, JSON.stringify(entity));
    }
    await assert.rejects(call('metadata.entity', { entity: ref('TRACK', '1') }), { code: 'UNSUPPORTED' });
  });
});
