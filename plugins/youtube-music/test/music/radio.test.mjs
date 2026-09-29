// Radio, as the app continued its queue (Media3MusicService), against recorded watch queues: a
// track's RDAMVM mix; an album's automix, read on its own; stations as they are.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { SIGNED_IN, browseOf, fixture, nextOf, offlinePlugin } from './helpers.mjs';

const SEED = 'i1OpHoRFGAw';
const ALBUM = { kind: 'ALBUM', providerId: 'MPREb_KHcXeTpvxEU' };
const ALBUM_PLAYLIST = 'OLAK5uy_lflrc2JHrdbM05aohCbP4riWF_xUwM8ys';
const AUTOMIX = `RDAMPL${ALBUM_PLAYLIST}`;

const ids = (list) => list.tracks.map((track) => track.ref.providerId);
const panelIds = (name) =>
  fixture(name)
    .contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs[0].tabRenderer.content.musicQueueRenderer.content.playlistPanelRenderer.contents.map(
      (content) => content.playlistPanelVideoRenderer?.videoId,
    )
    .filter(Boolean);

describe('a track radio', () => {
  test("is the track's RDAMVM mix in YouTube's order, without the track itself", async () => {
    const { call, calls } = offlinePlugin([[nextOf({ videoId: SEED, playlistId: `RDAMVM${SEED}` }), 'next_track']]);
    const list = await call('metadata.radio', { seed: { kind: 'TRACK', providerId: SEED } });
    assert.deepEqual(ids(list), panelIds('next_track').filter((id) => id !== SEED));
    assert.deepEqual(list.source, { kind: 'RADIO', providerId: `RDAMVM${SEED}` });
    assert.equal(calls.length, 1);
    const first = list.tracks[0];
    assert.equal(first.title, 'Feed Your Head');
    assert.deepEqual(first.artists.map((artist) => artist.name), ['Paul Kalkbrenner']);
    assert.equal(first.album, '7');
    assert.equal(first.albumRef.kind, 'ALBUM');
    assert.equal(first.durationMs, 347000);
    assert.equal(first.ids.ytm, first.ref.providerId);
  });

  test('continues from its cursor with the same endpoint', async () => {
    const { call, calls } = offlinePlugin([[nextOf({ videoId: SEED }), 'next_track']]);
    const list = await call('metadata.radio', { seed: { kind: 'TRACK', providerId: SEED } });
    await call('metadata.radio', { seed: { kind: 'TRACK', providerId: SEED }, cursor: list.next });
    const continued = calls.at(-1).body;
    assert.equal(continued.videoId, SEED);
    assert.equal(continued.playlistId, `RDAMVM${SEED}`);
    assert.ok(continued.continuation.startsWith('CDIS'));
  });

  test('falls back to the plain watch queue when the mix has nothing after the track', async () => {
    const lonely = structuredClone(fixture('next_track'));
    const panel = lonely.contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs[0].tabRenderer.content.musicQueueRenderer.content.playlistPanelRenderer;
    panel.contents = panel.contents.slice(0, 1);
    const { call, calls } = offlinePlugin([
      [nextOf({ videoId: SEED, playlistId: `RDAMVM${SEED}` }), lonely],
      [nextOf({ videoId: SEED, playlistId: undefined }), 'next_track'],
    ]);
    const list = await call('metadata.radio', { seed: { kind: 'TRACK', providerId: SEED } });
    assert.equal(calls.length, 2);
    assert.equal(list.tracks.length, 5);
  });

  test("is the signed-in listener's own mix, and the anonymous one when that fails", async () => {
    const { call, calls } = offlinePlugin(
      [
        [(it) => it.endpoint === 'next' && it.signed, () => ({})],
        [nextOf({ videoId: SEED }), 'next_track'],
      ],
      { secrets: SIGNED_IN },
    );
    const list = await call('metadata.radio', { seed: { kind: 'TRACK', providerId: SEED } });
    assert.deepEqual(calls.map((it) => it.signed), [true, false]);
    assert.equal(list.tracks.length, 5);
  });
});

describe('an album radio', async () => {
  const routes = [
    [browseOf(ALBUM.providerId), 'album_7'],
    [nextOf({ playlistId: ALBUM_PLAYLIST }), 'next_album'],
    [nextOf({ playlistId: AUTOMIX, params: 'wAEB8gECeAE%3D' }), 'next_album_automix'],
    [nextOf({ playlistId: AUTOMIX, params: undefined }), 'next_album_mix'],
  ];
  const { call, calls } = offlinePlugin(routes);
  const list = await call('metadata.radio', { seed: ALBUM });

  test("reads the album's watch queue for its automix, then that automix on its own (Metrolist's getAutomix)", () => {
    assert.deepEqual(
      calls.map((it) => [it.endpoint, it.body.browseId ?? it.body.playlistId, it.body.params]),
      [
        ['browse', ALBUM.providerId, undefined],
        ['next', ALBUM_PLAYLIST, undefined],
        ['next', AUTOMIX, 'wAEB8gECeAE%3D'],
        ['next', AUTOMIX, undefined],
      ],
    );
  });

  test("is the automix's similar content in YouTube's order, never the album again", () => {
    assert.deepEqual(ids(list), panelIds('next_album_mix'));
    const album = new Set(panelIds('next_album'));
    assert.ok(ids(list).every((id) => !album.has(id)));
    assert.deepEqual(list.source, { kind: 'RADIO', providerId: AUTOMIX });
    assert.ok(list.next);
  });

  test('a playlist seed skips the album lookup', async () => {
    const playlist = offlinePlugin(routes);
    const same = await playlist.call('metadata.radio', { seed: { kind: 'PLAYLIST', providerId: ALBUM_PLAYLIST } });
    assert.deepEqual(ids(same), ids(list));
    assert.equal(playlist.calls[0].endpoint, 'next');
  });
});

describe('stations', () => {
  test("an artist's station is played as it is", async () => {
    const station = 'RDEMrKT7MiRTXV0lBmFLcMBxPg';
    for (const kind of ['RADIO', 'PLAYLIST']) {
      const { call, calls } = offlinePlugin([[nextOf({ playlistId: station }), 'next_track']]);
      const list = await call('metadata.radio', { seed: { kind, providerId: station } });
      assert.equal(calls.length, 1, kind);
      assert.ok(list.tracks.length >= 5, kind);
      assert.deepEqual(list.source, { kind: 'RADIO', providerId: station }, kind);
    }
  });

  test('a collection with no mix hands over to the mix of its last track', async () => {
    const empty = { contents: { singleColumnMusicWatchNextResultsRenderer: { tabbedRenderer: { watchNextTabbedResultsRenderer: { tabs: [{ tabRenderer: { content: { musicQueueRenderer: { content: { playlistPanelRenderer: { contents: [] } } } } } }] } } } } };
    const { call, calls } = offlinePlugin([
      [nextOf({ playlistId: 'PLx' }), 'next_track'],
      [nextOf({ playlistId: 'RDAMPLPLx' }), empty],
      [nextOf({ videoId: 'on10QBCg9YI' }), 'next_track'],
    ]);
    const list = await call('metadata.radio', { seed: { kind: 'PLAYLIST', providerId: 'PLx' } });
    assert.equal(calls.at(-1).body.playlistId, 'RDAMVMon10QBCg9YI');
    assert.ok(!ids(list).includes('on10QBCg9YI'));
  });

  test('a track, album or playlist is the only kind of seed besides a station', async () => {
    const { call } = offlinePlugin([]);
    await assert.rejects(call('metadata.radio', { seed: { kind: 'PROFILE', providerId: 'UC1' } }), { code: 'UNSUPPORTED' });
  });
});
