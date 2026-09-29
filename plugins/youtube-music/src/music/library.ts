// The signed-in listener's YouTube Music library, as the app's AccountFeedClient and
// AccountFeedsViewModel read it: liked music (playlist LM), the listening history
// (FEmusic_history) and the saved playlists (FEmusic_liked_playlists).
import type { LibraryRequest, MetadataItem, MetadataPage, PageBlock, PageBlockCollection } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { type Json, dig } from '../util';
import { browse, signedIn } from './api';
import { card } from './cards';
import { collectionPage, tracksContinuationPage } from './pages';
import { nextContinuation, runsText } from './renderers';
import { rows } from './rows';
import { trackTable, uniqueBlocks } from './shelves';

export const LIKED_MUSIC_PLAYLIST_ID = 'LM';
const HISTORY_BROWSE_ID = 'FEmusic_history';
const PLAYLISTS_BROWSE_ID = 'FEmusic_liked_playlists';
const LIKED = { kind: 'PLAYLIST', providerId: LIKED_MUSIC_PLAYLIST_ID } as const;

const pageId = (section: string) => `youtube-music/library/${section}`;
const firstSections = (response: Json): Json[] =>
  dig(response, 'contents', 'singleColumnBrowseResultsRenderer', 'tabs', 0, 'tabRenderer', 'content', 'sectionListRenderer', 'contents') ?? [];

/** The history, one table per period YouTube groups it by ("Today", "Last week"…). */
export function historyPage(response: Json): MetadataPage {
  const blocks = firstSections(response).map((content) => {
    const shelf = content?.musicShelfRenderer;
    if (!shelf) return undefined;
    const title = runsText(shelf.title);
    return trackTable(title ?? 'history', title, shelf.contents ?? []);
  });
  return { id: pageId('history'), blocks: uniqueBlocks(blocks) };
}

/** Saved playlists (and albums) from the library grid or list, with the token of their next page. */
function libraryItems(container: Json, blockId: string): { items: MetadataItem[]; continuation?: string } {
  const grid = container?.gridRenderer ?? container?.gridContinuation;
  if (grid) {
    const items = (grid.items ?? []).map((item: Json) => item?.musicTwoRowItemRenderer && card(item.musicTwoRowItemRenderer, blockId, true)).filter(Boolean);
    return { items, continuation: nextContinuation(grid.continuations) };
  }
  const shelf = container?.musicShelfRenderer ?? container?.musicShelfContinuation;
  return { items: rows(shelf?.contents ?? [], blockId), continuation: nextContinuation(shelf?.continuations) };
}

function playlistsBlock(items: MetadataItem[], layout: PageBlockCollection['layout']): PageBlockCollection | undefined {
  if (items.length === 0) return undefined;
  return {
    type: 'collection',
    id: 'playlists',
    header: { title: 'Playlists' },
    layout,
    defaultItemView: layout === 'TRACK_TABLE' ? 'TRACK_ROW' : 'COVER_CARD',
    items,
  };
}

export function playlistsPage(response: Json, continuation: boolean): MetadataPage {
  const container = continuation ? response?.continuationContents : firstSections(response)[0];
  const { items, continuation: next } = libraryItems(container, 'playlists');
  return { id: pageId('playlists'), blocks: uniqueBlocks([playlistsBlock(items, 'TRACK_TABLE')]), nextCursor: next };
}

/** Liked music, the latest listens and the saved playlists, each opening its own section. */
export function overviewPage(liked?: Json, history?: Json, playlists?: Json): MetadataPage {
  const blocks: (PageBlock | undefined)[] = [];
  if (liked) {
    const page = collectionPage(liked, LIKED).blocks;
    const header = page.find((block) => block.type === 'header');
    const tracks = page.find((block): block is PageBlockCollection => block.type === 'collection' && block.layout === 'TRACK_TABLE');
    if (tracks) {
      blocks.push({
        ...tracks,
        id: 'liked',
        header: { title: header?.type === 'header' && header.title ? header.title : 'Liked music' },
        layout: 'MULTI_COLUMN_LIST',
        showAll: LIKED,
        items: tracks.items.map((item) => ({ ...item, id: `liked#${item.entity.providerId}` })),
      });
    }
  }
  if (history) {
    const items = historyPage(history).blocks.flatMap((block) => (block.type === 'collection' ? block.items : []));
    const unique = items.filter((item, index) => items.findIndex((other) => other.entity.providerId === item.entity.providerId) === index);
    if (unique.length > 0) {
      blocks.push({ type: 'collection', id: 'history', header: { title: 'Recently played' }, layout: 'MULTI_COLUMN_LIST', defaultItemView: 'TRACK_ROW', items: unique.map((item) => ({ ...item, id: `history#${item.entity.providerId}` })) });
    }
  }
  if (playlists) blocks.push(playlistsBlock(libraryItems(firstSections(playlists)[0], 'playlists').items, 'HORIZONTAL_SHELF'));
  return { id: pageId('overview'), blocks: uniqueBlocks(blocks) };
}

export async function library(request: LibraryRequest): Promise<MetadataPage> {
  if (!(await signedIn())) fail('SIGN_IN_REQUIRED', 'The library needs a signed-in account');
  const { section, cursor } = request;
  switch (section ?? null) {
    case 'history':
      return historyPage(await browse({ browseId: HISTORY_BROWSE_ID }));
    case 'liked':
      if (cursor) return tracksContinuationPage(await browse({ continuation: cursor }));
      return { ...collectionPage(await browse({ browseId: `VL${LIKED_MUSIC_PLAYLIST_ID}` }), LIKED), id: pageId('liked') };
    case 'playlists':
      if (cursor) return playlistsPage(await browse({ continuation: cursor }), true);
      return playlistsPage(await browse({ browseId: PLAYLISTS_BROWSE_ID }), false);
    case null: {
      const parts = await Promise.allSettled([
        browse({ browseId: `VL${LIKED_MUSIC_PLAYLIST_ID}` }),
        browse({ browseId: HISTORY_BROWSE_ID }),
        browse({ browseId: PLAYLISTS_BROWSE_ID }),
      ]);
      const value = (index: number) => (parts[index].status === 'fulfilled' ? (parts[index] as PromiseFulfilledResult<Json>).value : undefined);
      if (parts.every((part) => part.status === 'rejected')) throw (parts[0] as PromiseRejectedResult).reason;
      return overviewPage(value(0), value(1), value(2));
    }
    default:
      return fail('NOT_FOUND', `No library section ${section}`);
  }
}
