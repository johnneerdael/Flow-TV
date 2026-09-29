// Every playable track of a collection, a page at a time, for the queue: an album's or playlist's rows
// as their page lists them, an artist's top songs through their playlist, and a mix through its
// watch queue when YouTube serves it no page.
import type { EntityRef, MetadataItem, TrackDescriptor, TrackList, TracksRequest } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { type Json, dig } from '../util';
import { type WatchEndpoint, browse, next } from './api';
import { decodeCursor, encodeCursor } from './cursor';
import { TRACKS_BLOCK_ID, collectionTracks, continuationRows } from './pages';
import { browseRef } from './renderers';
import { rows } from './rows';
import { watchPage } from './watch';

const descriptors = (items: MetadataItem[]): TrackDescriptor[] =>
  items.map((item) => item.track).filter((track): track is TrackDescriptor => !!track);

const browseNext = (continuation: string | undefined) => (continuation ? encodeCursor({ type: 'browse', continuation }) : undefined);

async function queue(endpoint: WatchEndpoint, continuation: string | undefined, source: EntityRef): Promise<TrackList> {
  const page = watchPage(await next(endpoint, continuation, true));
  if (!page) fail('NOT_FOUND', `No watch queue for ${endpoint.playlistId}`);
  return {
    tracks: page.tracks,
    next: page.continuation && page.tracks.length > 0 ? encodeCursor({ type: 'queue', endpoint, continuation: page.continuation }) : undefined,
    source,
  };
}

async function collection(entity: EntityRef): Promise<TrackList> {
  const browseId = entity.kind === 'ALBUM' ? entity.providerId : `VL${entity.providerId}`;
  const response = await browse({ browseId });
  const { contents, options, continuation } = collectionTracks(response, entity);
  const tracks = descriptors(rows(contents, TRACKS_BLOCK_ID, options));
  if (tracks.length === 0 && entity.kind !== 'ALBUM') return queue({ playlistId: entity.providerId }, undefined, entity);
  return { tracks, next: browseNext(continuation), source: entity };
}

/** An artist's top songs: the playlist its Top songs shelf opens, or the shelf's rows when it has none. */
async function artistTopSongs(artist: EntityRef): Promise<TrackList> {
  const response = await browse({ browseId: artist.providerId });
  const sections: Json[] = dig(response, 'contents', 'singleColumnBrowseResultsRenderer', 'tabs', 0, 'tabRenderer', 'content', 'sectionListRenderer', 'contents') ?? [];
  const shelf = sections.find((content) => content?.musicShelfRenderer)?.musicShelfRenderer;
  const all = browseRef(shelf?.bottomEndpoint?.browseEndpoint);
  if (all?.kind === 'PLAYLIST') return collection(all);
  return { tracks: descriptors(rows(shelf?.contents ?? [], TRACKS_BLOCK_ID)), source: artist };
}

export async function tracks(request: TracksRequest): Promise<TrackList> {
  const { entity, cursor } = request;
  if (cursor) {
    const decoded = decodeCursor(cursor, 'browse', 'queue');
    if (decoded.type === 'queue') return queue(decoded.endpoint, decoded.continuation, entity);
    const { contents, continuation } = continuationRows(await browse({ continuation: decoded.continuation }));
    return { tracks: descriptors(rows(contents, TRACKS_BLOCK_ID)), next: browseNext(continuation), source: entity };
  }
  switch (entity.kind) {
    case 'ALBUM':
    case 'PLAYLIST':
    case 'MIX':
      return collection(entity);
    case 'ARTIST':
      return artistTopSongs(entity);
    default:
      return fail('UNSUPPORTED', `No tracks for ${entity.kind}`);
  }
}
