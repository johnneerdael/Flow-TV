// Playable lists (charts, playlists and the Top 100s) and plain lists (top releases, new charts, the
// genres, the listener's picks). A playable list is a header with Play all over a numbered table;
// its further pages extend that table.
import type { EntityRef, MetadataItem, MetadataPage, PageBlockHeader } from '@milkbeat/plugin-sdk';
import type { PlaylistSource } from '../api/playlists';
import type { Chart, Playlist, Track } from '../api/types';
import { counted, isPresent } from '../util';
import { LARGE, artwork, fixed } from './artwork';
import { present, table } from './blocks';
import { bpmRange } from './items';
import { refs } from './refs';
import { trackItems } from './tracks';

export const TRACKS_BLOCK_ID = 'tracks';
export const LIST_BLOCK_ID = 'list';

const pageId = (entity: EntityRef) => `beatport/${entity.kind.toLowerCase()}/${entity.providerId}`;

function duration(ms: number | undefined): string | undefined {
  if (!ms) return undefined;
  const minutes = Math.round(ms / 60000);
  return minutes >= 60 ? `${Math.floor(minutes / 60)} hr ${minutes % 60} min` : `${minutes} min`;
}

export function chartHeader(chart: Chart): PageBlockHeader {
  const entity = refs.chart(chart.id);
  const curator = chart.person?.owner_name ?? chart.artist?.name;
  return {
    type: 'header',
    id: 'header',
    style: 'COVER',
    entity,
    title: chart.name ?? '',
    artwork: artwork(chart.image, LARGE),
    attribution: curator
      ? { name: curator, avatar: chart.person?.owner_image ? fixed(chart.person.owner_image, LARGE) : undefined, entity: chart.artist ? refs.artist(chart.artist.id) : undefined }
      : undefined,
    details: [
      chart.genres?.map((genre) => genre.name).filter(isPresent).join(', ') || undefined,
      chart.track_count ? counted(chart.track_count, 'track') : undefined,
      chart.publish_date?.slice(0, 10),
    ].filter(isPresent),
    description: chart.description?.trim() || undefined,
    tracks: entity,
  };
}

export function playlistHeader(source: PlaylistSource, playlist: Playlist): PageBlockHeader {
  const entity = refs.playlist(source, playlist.id);
  return {
    type: 'header',
    id: 'header',
    style: 'COVER',
    entity,
    title: playlist.name ?? '',
    artwork: fixed(playlist.release_images?.[0], LARGE),
    details: [
      playlist.genres?.join(', ') || playlist.genre?.name,
      playlist.track_count !== undefined ? counted(playlist.track_count, 'track') : undefined,
      duration(playlist.length_ms),
      bpmRange(playlist.bpm_range),
    ].filter(isPresent),
    tracks: entity,
  };
}

/**
 * A header for a list Beatport gives no detail of, such as a Top 100: its title, and its genre or
 * artist; [playable] lists get Play all.
 */
export function simpleHeader(entity: EntityRef, title: string, subtitle: string | undefined, playable: boolean): PageBlockHeader {
  return { type: 'header', id: 'header', style: 'COVER', entity, title, details: subtitle ? [subtitle] : [], tracks: playable ? entity : undefined };
}

/** A playable list's page: its header on the first page only, and its tracks numbered on from [firstOrdinal]. */
export function trackListPage(entity: EntityRef, header: PageBlockHeader | undefined, tracks: Track[], firstOrdinal: number, nextCursor: string | undefined): MetadataPage {
  return {
    id: pageId(entity),
    blocks: present([header, table(TRACKS_BLOCK_ID, undefined, trackItems(tracks, TRACKS_BLOCK_ID, firstOrdinal))]),
    nextCursor,
  };
}

/** A list of anything, under a title on its first page. */
export function listPage(entity: EntityRef, title: string | undefined, items: MetadataItem[], nextCursor?: string, header?: PageBlockHeader): MetadataPage {
  return { id: pageId(entity), blocks: present([header, table(LIST_BLOCK_ID, header ? undefined : title, items)]), nextCursor };
}
