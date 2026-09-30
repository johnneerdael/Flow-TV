// The library from fixtures made up from public data (nothing of the recording account is kept):
// the overview, each section in full, and the listener's playlists.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { IDS } from '../scripts/scenario.mjs';
import { collections, header, offlinePlugin } from './helpers.mjs';

const refText = (entity) => (entity ? `${entity.kind}:${entity.providerId}` : null);

describe('library', async () => {
  const { call } = offlinePlugin();

  test('the overview: playlists, My Beatport, followed artists and labels, each opening its section', async () => {
    const page = await call('metadata.library', {});
    assert.deepEqual(
      collections(page).map((shelf) => [shelf.id, shelf.layout, refText(shelf.showAll)]),
      [
        ['playlists', 'HORIZONTAL_SHELF', 'MIX:my:playlists'],
        ['tracks', 'MULTI_COLUMN_LIST', 'MIX:my:tracks'],
        ['artists', 'HORIZONTAL_SHELF', 'MIX:my:artists'],
        ['labels', 'HORIZONTAL_SHELF', 'MIX:my:labels'],
      ],
    );
  });

  test('a section is the same page as its show-all', async () => {
    for (const section of ['playlists', 'tracks', 'artists', 'labels']) {
      const fromLibrary = await call('metadata.library', { section });
      const fromEntity = await call('metadata.entity', { entity: { kind: 'MIX', providerId: `my:${section}` } });
      assert.deepEqual(fromEntity, fromLibrary, section);
    }
  });

  test('My Beatport pages on; followed artists and labels come whole', async () => {
    const first = await call('metadata.library', { section: 'tracks' });
    assert.equal(first.nextCursor, '2');
    const second = await call('metadata.library', { section: 'tracks', cursor: '2' });
    assert.equal(second.blocks[0].id, 'tracks');
    assert.equal(second.nextCursor, undefined);
    assert.equal((await call('metadata.library', { section: 'artists' })).nextCursor, undefined);
    await assert.rejects(call('metadata.library', { section: 'labels', cursor: '2' }), { code: 'NOT_FOUND' });
  });

  test('labels are profiles, artists portraits', async () => {
    const labels = (await call('metadata.library', { section: 'labels' })).blocks[0].items;
    assert.ok(labels.every((item) => item.entity.kind === 'PROFILE' && item.entity.providerId.startsWith('label:')));
    const artists = (await call('metadata.library', { section: 'artists' })).blocks[0].items;
    assert.ok(artists.every((item) => item.view === 'ARTIST_PORTRAIT'));
  });

  test('the listener’s playlist opens under my/, with its tracks unwrapped', async () => {
    const page = await call('metadata.entity', { entity: { kind: 'PLAYLIST', providerId: `mine:${IDS.myPlaylist}` } });
    assert.equal(header(page).title, 'Playlist A');
    assert.ok(page.blocks[1].items.every((item) => item.track));
  });

  test('sections YouTube has and Beatport has not are not found', async () => {
    await assert.rejects(call('metadata.library', { section: 'history' }), { code: 'NOT_FOUND' });
  });

  test('without a session the library asks to sign in', async () => {
    const anonymous = offlinePlugin({ secrets: {} });
    await assert.rejects(anonymous.call('metadata.library', {}), { code: 'SIGN_IN_REQUIRED' });
    assert.deepEqual(await anonymous.call('signIn.account', {}), { type: 'anonymous' });
  });

  test('a signed-in account is keyed without its token', async () => {
    const account = await call('signIn.account', {});
    assert.equal(account.type, 'signedIn');
    assert.match(account.key, /^[0-9a-f]{32}$/);
    assert.ok(!account.key.includes('fixture-token'));
  });
});
