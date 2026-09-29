// A track row (musicResponsiveListItemRenderer) as YouTube Music lays it out: the title, then columns
// of credits and play counts, one of which may name the album; the duration sits in a fixed column.
// Ported from the app's YouTubeShelfMapper.row.
import type { ArtistCredit, Artwork, EntityRef, MetadataItem, TrackDescriptor } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import { musicThumbnail } from './artwork';
import {
  browseRef,
  columnRuns,
  columnText,
  isExplicit,
  isVideoType,
  musicVideoType,
  parseTime,
  playEndpoint,
  thumbnailUrl,
} from './renderers';

const ROW_ARTWORK_SIZE = 256;

export interface RowOptions {
  /** The row's number, as on an album. */
  ordinal?: number;
  /** Who a row that names no artist of its own is credited to: an album's tracks are the album's. */
  defaultArtists?: ArtistCredit[];
  /** The album a row belongs to when the page is that album; only its track descriptor names it. */
  album?: { name: string; ref: EntityRef };
  /** The track's artwork when the row has none, as an album's cover. */
  fallbackArtwork?: Artwork;
}

const joined = (runs: Json[]) => runs.map((run) => run?.text ?? '').join('');
const hasText = (runs: Json[]) => runs.some((run) => String(run?.text ?? '').trim() !== '');

/** A playable row in collection [blockId], or undefined for a row that plays nothing. */
export function row(renderer: Json, blockId: string, options: RowOptions = {}): MetadataItem | undefined {
  const columns: Json[][] = (Array.isArray(renderer.flexColumns) ? renderer.flexColumns : []).map(columnRuns);
  const titleRuns = columns[0] ?? [];
  const title = joined(titleRuns);
  if (!title.trim()) return undefined;
  const titleWatch = titleRuns.find((run) => run?.navigationEndpoint?.watchEndpoint?.videoId)?.navigationEndpoint?.watchEndpoint;
  const videoId: string | undefined = renderer.playlistItemData?.videoId ?? titleWatch?.videoId;
  if (!videoId) return undefined;
  const videoType =
    musicVideoType(playEndpoint(renderer.overlay)) ??
    musicVideoType(renderer.navigationEndpoint) ??
    titleRuns.map((run) => musicVideoType(run?.navigationEndpoint)).find((type) => type !== undefined);

  const isAlbumRun = (run: Json) => browseRef(run?.navigationEndpoint?.browseEndpoint)?.kind === 'ALBUM';
  const rest = columns.slice(1).filter(hasText);
  const albumColumns = rest.filter((runs) => runs.some(isAlbumRun));
  const detailColumns = rest.filter((runs) => !runs.some(isAlbumRun));
  const artistRuns = detailColumns.flat().filter((run) => browseRef(run?.navigationEndpoint?.browseEndpoint)?.kind === 'ARTIST');
  const credits: ArtistCredit[] = artistRuns.map((run) => ({ name: run.text, entity: browseRef(run.navigationEndpoint.browseEndpoint) }));
  const artists = credits.length > 0 ? credits : (options.defaultArtists ?? []);

  const albumRun = albumColumns[0]?.find(isAlbumRun);
  const rawThumbnail = thumbnailUrl(renderer.thumbnail);
  const artwork = rawThumbnail ? { url: musicThumbnail(videoId, rawThumbnail, ROW_ARTWORK_SIZE) } : undefined;
  const durationSeconds = parseTime(columnText(renderer.fixedColumns?.[0]));
  const explicit = isExplicit(renderer.badges);
  const hasVideo = isVideoType(videoType);
  const entity: EntityRef = { kind: hasVideo ? 'MUSIC_VIDEO' : 'TRACK', providerId: videoId };
  const subtitle = detailColumns.map(joined).join(' • ');

  const track: TrackDescriptor = {
    ref: entity,
    title,
    artists,
    album: albumColumns[0] ? joined(albumColumns[0]) : options.album?.name,
    albumRef: albumRun ? browseRef(albumRun.navigationEndpoint.browseEndpoint) : options.album?.ref,
    durationMs: durationSeconds !== undefined ? durationSeconds * 1000 : undefined,
    explicit,
    artwork: artwork ?? options.fallbackArtwork,
    trackNumber: options.album ? options.ordinal : undefined,
    hasVideo,
    ids: { ytm: videoId },
  };
  return {
    id: `${blockId}#${videoId}`,
    entity,
    title,
    subtitle: subtitle.trim() ? subtitle : undefined,
    artwork,
    artists,
    durationSeconds,
    explicit,
    ordinal: options.ordinal,
    album: albumColumns[0] ? joined(albumColumns[0]) : undefined,
    track,
  };
}

/** The playable rows among [contents] (shelf contents or bare renderers), in order, each track once. */
export function rows(contents: Json[], blockId: string, options: Omit<RowOptions, 'ordinal'> & { numbered?: boolean } = {}): MetadataItem[] {
  const renderers = contents.map((content) => content?.musicResponsiveListItemRenderer ?? content).filter((it) => it?.flexColumns);
  const items: MetadataItem[] = [];
  renderers.forEach((renderer, index) => {
    const item = row(renderer, blockId, { ...options, ordinal: options.numbered ? index + 1 : undefined });
    if (item && !items.some((seen) => sameEntity(seen.entity, item.entity))) items.push(item);
  });
  return items;
}

export function sameEntity(a: EntityRef, b: EntityRef): boolean {
  return a.kind === b.kind && a.providerId === b.providerId;
}
