// Entity pages: artists and profiles, albums, playlists and mixes, as the listener when signed in.
// A generated mix YouTube serves no page for (a track's RDAMVM mix) is shown as its watch queue.
import type { EntityRef, MetadataItem, MetadataPage, PageRequest } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { type WatchEndpoint, browse, next } from './api';
import { decodeCursor, encodeCursor } from './cursor';
import { TRACKS_BLOCK_ID, artistPage, collectionPage, tracksContinuationPage } from './pages';
import { uniqueBlocks } from './shelves';
import { type WatchPage, watchPage } from './watch';

function queueItems(page: WatchPage): MetadataItem[] {
  return page.tracks.map((track) => ({
    id: `${TRACKS_BLOCK_ID}#${track.ref.providerId}`,
    entity: track.ref,
    title: track.title,
    subtitle: (track.artists ?? []).map((artist) => artist.name).join(', ') || undefined,
    artwork: track.artwork,
    artists: track.artists,
    durationSeconds: track.durationMs ? Math.round(track.durationMs / 1000) : undefined,
    explicit: track.explicit,
    album: track.album,
    track,
  }));
}

/** A mix's watch queue as a page: its title and cover, then its tracks, paging on through the queue. */
export function queueEntityPage(page: WatchPage, entity: EntityRef, endpoint: WatchEndpoint, first: boolean): MetadataPage {
  const items = queueItems(page);
  const next = page.continuation && items.length > 0 ? encodeCursor({ type: 'queue', endpoint, continuation: page.continuation }) : undefined;
  const header =
    first && page.title
      ? { type: 'header' as const, id: `${entity.providerId}/header`, style: 'COVER' as const, entity, title: page.title, artwork: items[0]?.artwork, tracks: entity }
      : undefined;
  const tracks = items.length > 0 ? { type: 'collection' as const, id: TRACKS_BLOCK_ID, header: null, layout: 'TRACK_TABLE' as const, defaultItemView: 'TRACK_ROW' as const, items } : undefined;
  return { id: first ? `youtube-music/${entity.kind.toLowerCase()}/${entity.providerId}` : TRACKS_BLOCK_ID, blocks: uniqueBlocks([header, tracks]), nextCursor: next };
}

async function queuePage(entity: EntityRef, endpoint: WatchEndpoint, continuation?: string): Promise<MetadataPage> {
  const page = watchPage(await next(endpoint, continuation, true));
  if (!page) fail('NOT_FOUND', `No page for ${entity.kind} ${entity.providerId}`);
  return queueEntityPage(page, entity, endpoint, continuation === undefined);
}

export async function entity(request: PageRequest): Promise<MetadataPage> {
  const { entity: ref, cursor } = request;
  if (cursor?.startsWith('{')) {
    const queue = decodeCursor(cursor, 'queue');
    return queuePage(ref, queue.endpoint, queue.continuation);
  }
  if (cursor) return tracksContinuationPage(await browse({ continuation: cursor }));
  let page: MetadataPage;
  switch (ref.kind) {
    case 'ARTIST':
    case 'PROFILE':
      page = artistPage(await browse({ browseId: ref.providerId }), ref);
      break;
    case 'ALBUM':
      page = collectionPage(await browse({ browseId: ref.providerId }), ref);
      break;
    case 'PLAYLIST':
    case 'MIX': {
      const generated = ref.kind === 'MIX' || ref.providerId.startsWith('RD');
      const response = await browse({ browseId: `VL${ref.providerId}` }).catch((error) => {
        if (generated) return undefined;
        throw error;
      });
      page = collectionPage(response, ref);
      if (page.blocks.length === 0 && generated) return queuePage(ref, { playlistId: ref.providerId });
      break;
    }
    default:
      return fail('UNSUPPORTED', `YouTube Music has no page for ${ref.kind}`);
  }
  if (page.blocks.length === 0) fail('NOT_FOUND', `No page for ${ref.kind} ${ref.providerId}`);
  return page;
}
