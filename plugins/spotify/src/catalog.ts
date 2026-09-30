import { fail } from '@milkbeat/plugin-sdk';
import type { EntityRef, TrackList } from '@milkbeat/plugin-sdk';
import { query } from './api';
import { LIKED, trackDescriptor } from './mapping';
import { at, items, object, text, uri } from './util';
import type { JsonObject } from './util';

export const PAGE_SIZE = 50;
export interface CollectionData { data: JsonObject; rows: unknown[]; total?: unknown; album?: JsonObject }

export function required(value: unknown): JsonObject {
  const data = object(value);
  if (!Object.keys(data).length || /NotFound|Restricted|Error/.test(text(data.__typename) ?? '')) fail('NOT_FOUND', 'This Spotify item is unavailable');
  return data;
}

export async function collectionData(ref: EntityRef, start: number): Promise<CollectionData> {
  const id = uri(ref);
  if (id === LIKED.providerId) {
    const data = required(at(await query('fetchLibraryTracks', { offset: start, limit: PAGE_SIZE }), 'me', 'library', 'tracks'));
    return { data: { name: 'Liked Songs', uri: id, content: data }, rows: items(data), total: data.totalCount };
  }
  if (ref.kind === 'ALBUM') {
    const data = required(at(await query('getAlbum', { uri: id, locale: '', offset: start, limit: PAGE_SIZE }), 'albumUnion'));
    const tracks = object(data.tracksV2);
    return { data, album: data, rows: items(tracks), total: tracks.totalCount };
  }
  if (ref.kind === 'PLAYLIST' || ref.kind === 'MIX') {
    const data = required(at(await query('fetchPlaylist', { uri: id, offset: start, limit: PAGE_SIZE, enableWatchFeedEntrypoint: false }), 'playlistV2'));
    const tracks = object(data.content);
    return { data, rows: items(tracks), total: tracks.totalCount };
  }
  if (ref.kind === 'ARTIST') {
    const data = required(at(await query('queryArtistOverview', { uri: id, locale: '', preReleaseV2: true }), 'artistUnion'));
    const rows = items(at(data, 'discography', 'topTracks'));
    return { data, rows, total: rows.length };
  }
  if (ref.kind === 'TRACK') {
    const data = required(at(await query('getTrack', { uri: id }), 'trackUnion'));
    return { data, rows: [data], total: 1 };
  }
  return fail('UNSUPPORTED', `Spotify has no track list for ${ref.kind}`);
}

export function descriptors(value: CollectionData): TrackList['tracks'] {
  return value.rows.flatMap((row) => {
    const track = trackDescriptor(row, value.album);
    return track ? [track] : [];
  });
}
