// The signed-in library (AccountFeedClient's music feeds): liked music, history and saved playlists.
// The account pages are built from the recorded playlist's rows, since fixtures hold no account data.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { SIGNED_IN, blocks, browseOf, continuationOf, fixture, header, offlinePlugin } from './helpers.mjs';

const playlist = fixture('youtube_music_playlist');
const rows = playlist.contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.contents[0].musicPlaylistShelfRenderer.contents.filter(
  (content) => content.musicResponsiveListItemRenderer,
);

/** Liked music as an editable playlist: its header sits inside the editable header, and it pages on. */
function likedMusic() {
  const liked = structuredClone(playlist);
  const sections = liked.contents.twoColumnBrowseResultsRenderer.tabs[0].tabRenderer.content.sectionListRenderer.contents;
  const index = sections.findIndex((section) => section.musicResponsiveHeaderRenderer);
  const responsive = sections[index].musicResponsiveHeaderRenderer;
  responsive.title = { runs: [{ text: 'Liked Music' }] };
  responsive.buttons = [];
  sections[index] = { musicEditablePlaylistDetailHeaderRenderer: { header: { musicResponsiveHeaderRenderer: responsive } } };
  const shelf = liked.contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.contents[0].musicPlaylistShelfRenderer;
  shelf.contents = [...rows, { continuationItemRenderer: { continuationEndpoint: { continuationCommand: { token: 'liked-more' } } } }];
  return liked;
}

const history = {
  contents: {
    singleColumnBrowseResultsRenderer: {
      tabs: [
        {
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [
                  { musicShelfRenderer: { title: { runs: [{ text: 'Today' }] }, contents: rows.slice(0, 3) } },
                  { musicShelfRenderer: { title: { runs: [{ text: 'Yesterday' }] }, contents: rows.slice(2, 6) } },
                ],
              },
            },
          },
        },
      ],
    },
  },
};

const card = (id, title) => ({
  musicTwoRowItemRenderer: {
    title: { runs: [{ text: title }] },
    subtitle: { runs: [{ text: '12 songs' }] },
    thumbnailRenderer: { musicThumbnailRenderer: { thumbnail: { thumbnails: [{ url: `https://lh3.googleusercontent.com/${id}=w226-h226` }] } } },
    navigationEndpoint: { browseEndpoint: { browseId: `VL${id}`, browseEndpointContextSupportedConfigs: { browseEndpointContextMusicConfig: { pageType: 'MUSIC_PAGE_TYPE_PLAYLIST' } } } },
  },
});
const newPlaylist = { musicTwoRowItemRenderer: { title: { runs: [{ text: 'New playlist' }] }, thumbnailRenderer: {}, navigationEndpoint: { createPlaylistEndpoint: {} } } };

const playlists = {
  contents: {
    singleColumnBrowseResultsRenderer: {
      tabs: [
        {
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [{ gridRenderer: { items: [newPlaylist, card('PLmine', 'Mine'), card('LM', 'Liked Music')], continuations: [{ nextContinuationData: { continuation: 'grid-more' } }] } }],
              },
            },
          },
        },
      ],
    },
  },
};

const routes = [
  [browseOf('VLLM'), () => likedMusic()],
  [browseOf('FEmusic_history'), history],
  [browseOf('FEmusic_liked_playlists'), playlists],
  [continuationOf('liked-more'), { onResponseReceivedActions: [{ appendContinuationItemsAction: { continuationItems: rows.slice(0, 2) } }] }],
  [continuationOf('grid-more'), { continuationContents: { gridContinuation: { items: [card('PLlater', 'Later')] } } }],
];

describe('library', () => {
  test('needs a signed-in account', async () => {
    const { call, calls } = offlinePlugin(routes);
    await assert.rejects(call('metadata.library', {}), { code: 'SIGN_IN_REQUIRED' });
    await assert.rejects(call('metadata.library', { section: 'history' }), { code: 'SIGN_IN_REQUIRED' });
    assert.equal(calls.length, 0);
  });

  test('opens on liked music, recent listens and saved playlists, read as the account', async () => {
    const { call, calls } = offlinePlugin(routes, { secrets: SIGNED_IN });
    const page = await call('metadata.library', {});
    assert.deepEqual(blocks(page).map((block) => [block.id, block.header.title, block.layout]), [
      ['liked', 'Liked Music', 'MULTI_COLUMN_LIST'],
      ['history', 'Recently played', 'MULTI_COLUMN_LIST'],
      ['playlists', 'Playlists', 'HORIZONTAL_SHELF'],
    ]);
    assert.deepEqual(blocks(page)[0].showAll, { kind: 'PLAYLIST', providerId: 'LM' });
    assert.ok(blocks(page)[0].items.every((item) => item.track));
    assert.equal(blocks(page)[1].items.length, 6);
    assert.deepEqual(blocks(page)[2].items.map((item) => item.entity), [
      { kind: 'PLAYLIST', providerId: 'PLmine' },
      { kind: 'PLAYLIST', providerId: 'LM' },
    ]);
    assert.ok(calls.every((it) => it.signed));
    const ids = blocks(page).flatMap((block) => block.items.map((item) => item.id));
    assert.equal(new Set(ids).size, ids.length);
  });

  test('shows what it could read when one part fails', async () => {
    const { call } = offlinePlugin(routes.filter(([match]) => !match({ endpoint: 'browse', body: { browseId: 'FEmusic_history' }, query: {} })), { secrets: SIGNED_IN });
    const page = await call('metadata.library', {});
    assert.deepEqual(blocks(page).map((block) => block.id), ['liked', 'playlists']);
  });

  test('history is grouped as YouTube groups it', async () => {
    const { call } = offlinePlugin(routes, { secrets: SIGNED_IN });
    const page = await call('metadata.library', { section: 'history' });
    assert.deepEqual(blocks(page).map((block) => [block.header.title, block.layout, block.items.length]), [
      ['Today', 'TRACK_TABLE', 3],
      ['Yesterday', 'TRACK_TABLE', 4],
    ]);
  });

  test('liked music is the LM playlist, with its header and more tracks behind its cursor', async () => {
    const { call } = offlinePlugin(routes, { secrets: SIGNED_IN });
    const page = await call('metadata.library', { section: 'liked' });
    assert.equal(header(page).title, 'Liked Music');
    assert.deepEqual(header(page).tracks, { kind: 'PLAYLIST', providerId: 'LM' });
    assert.equal(blocks(page)[0].items.length, rows.length);
    assert.equal(page.nextCursor, 'liked-more');
    const more = await call('metadata.library', { section: 'liked', cursor: page.nextCursor });
    assert.equal(more.blocks[0].id, 'tracks');
    assert.equal(more.blocks[0].items.length, 2);
  });

  test('saved playlists list every playlist, paging on', async () => {
    const { call } = offlinePlugin(routes, { secrets: SIGNED_IN });
    const page = await call('metadata.library', { section: 'playlists' });
    assert.deepEqual(blocks(page)[0].items.map((item) => item.title), ['Mine', 'Liked Music']);
    assert.equal(page.nextCursor, 'grid-more');
    const more = await call('metadata.library', { section: 'playlists', cursor: page.nextCursor });
    assert.deepEqual(blocks(more)[0].items.map((item) => item.entity.providerId), ['PLlater']);
  });

  test('an unknown section is not found', async () => {
    const { call } = offlinePlugin(routes, { secrets: SIGNED_IN });
    await assert.rejects(call('metadata.library', { section: 'albums' }), { code: 'NOT_FOUND' });
  });
});
