// Search results as catalog items, after the app's SearchPage/SearchSummaryPage parsers: a row's
// second line reads "[type •] artists [• album] [• duration]", split at its separators; the top
// result is a card of its own. Podcasts and episodes are not part of the YouTube Music catalog.
import type { ArtistCredit, EntityRef, MetadataItem, TrackDescriptor } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import { musicThumbnail } from './artwork';
import {
  browseRef,
  columnRuns,
  columnText,
  isExplicit,
  isVideoType,
  musicVideoType,
  oddElements,
  parseTime,
  playEndpoint,
  runsText,
  splitBySeparator,
  thumbnailUrl,
} from './renderers';

export const ROW_ARTWORK_SIZE = 256;
export const CARD_ARTWORK_SIZE = 544;

const segmentText = (segment: Json[]) => segment.map((run) => run?.text ?? '').join('');

/** Drops the leading type label ("Song", "Album") of an unfiltered result's line, as the app's clean(). */
function clean(segments: Json[][]): Json[][] {
  const first = segments[0]?.[0];
  if (!first || first.navigationEndpoint || /[&,]/.test(String(first.text ?? ''))) return segments;
  return segments.slice(1);
}

function credits(segment: Json[] | undefined): ArtistCredit[] {
  return oddElements(segment ?? [])
    .filter((run) => String(run?.text ?? '').trim())
    .map((run) => {
      const ref = browseRef(run?.navigationEndpoint?.browseEndpoint);
      return ref && (ref.kind === 'ARTIST' || ref.kind === 'PROFILE') ? { name: run.text, entity: ref } : { name: run.text };
    });
}

function viewOf(entity: EntityRef): MetadataItem['view'] {
  if (entity.kind === 'ARTIST' || entity.kind === 'PROFILE') return 'ARTIST_PORTRAIT';
  return entity.kind === 'MUSIC_VIDEO' ? 'LANDSCAPE_CARD' : undefined;
}

/**
 * A result row: a track or video, or an album, artist, playlist or profile to open. A row that names
 * no artist (the songs under an artist's top result) is credited to [defaultArtists].
 */
export function resultRow(
  renderer: Json,
  blockId: string,
  summary: boolean,
  artworkSize = ROW_ARTWORK_SIZE,
  defaultArtists: ArtistCredit[] = [],
): MetadataItem | undefined {
  const columns: Json[][] = (Array.isArray(renderer?.flexColumns) ? renderer.flexColumns : []).map(columnRuns);
  const title = runsText({ runs: columns[0] ?? [] });
  if (!title) return undefined;
  const line = splitBySeparator(columns[1] ?? []).filter((segment) => segment.length > 0);
  const extra = columns.slice(2).flatMap((runs) => splitBySeparator(runs)).filter((segment) => segment.length > 0);
  // The credits line without its type label and duration: artists, then the album if there is one.
  const credited = (summary ? clean(line) : line).filter((segment) => parseTime(segmentText(segment)) === undefined);
  const rawThumbnail = thumbnailUrl(renderer.thumbnail);
  const watch = playEndpoint(renderer.overlay)?.watchEndpoint;
  const videoId: string | undefined =
    renderer.playlistItemData?.videoId ?? watch?.videoId ?? columns[0]?.find((run) => run?.navigationEndpoint?.watchEndpoint)?.navigationEndpoint.watchEndpoint.videoId;

  if (videoId) {
    const videoType =
      musicVideoType(playEndpoint(renderer.overlay)) ??
      musicVideoType(renderer.navigationEndpoint) ??
      columns[0]?.map((run) => musicVideoType(run?.navigationEndpoint)).find(Boolean);
    const lastLine = line[line.length - 1];
    const durationSeconds = parseTime(lastLine ? segmentText(lastLine) : undefined) ?? parseTime(columnText(renderer.fixedColumns?.[0]));
    const albumRun = credited[1]?.[0];
    const albumRef = browseRef(albumRun?.navigationEndpoint?.browseEndpoint);
    const named = credits(credited[0]);
    const artists = named.length > 0 ? named : defaultArtists;
    const explicit = isExplicit(renderer.badges);
    const hasVideo = isVideoType(videoType);
    const entity: EntityRef = { kind: hasVideo ? 'MUSIC_VIDEO' : 'TRACK', providerId: videoId };
    const artwork = rawThumbnail ? { url: musicThumbnail(videoId, rawThumbnail, artworkSize) } : undefined;
    const album = albumRef?.kind === 'ALBUM' ? { name: String(albumRun.text), ref: albumRef } : undefined;
    const track: TrackDescriptor = {
      ref: entity,
      title,
      artists,
      album: album?.name,
      albumRef: album?.ref,
      durationMs: durationSeconds !== undefined ? durationSeconds * 1000 : undefined,
      explicit,
      artwork,
      hasVideo,
      ids: { ytm: videoId },
    };
    const subtitle = [...line, ...extra]
      .filter((segment) => parseTime(segmentText(segment)) === undefined)
      .map(segmentText)
      .join(' • ');
    return {
      id: `${blockId}#${videoId}`,
      entity,
      title,
      subtitle: subtitle || undefined,
      artwork,
      view: viewOf(entity),
      artists,
      durationSeconds,
      explicit,
      album: album?.name,
      track,
    };
  }

  const entity = browseRef(renderer.navigationEndpoint?.browseEndpoint);
  if (!entity) return undefined;
  const subtitle = [...line, ...extra].map(segmentText).join(' • ');
  return {
    id: `${blockId}#${entity.providerId}`,
    entity,
    title,
    subtitle: subtitle || undefined,
    artwork: rawThumbnail ? { url: musicThumbnail('', rawThumbnail, CARD_ARTWORK_SIZE) } : undefined,
    view: viewOf(entity),
    artists: entity.kind === 'ALBUM' || entity.kind === 'PLAYLIST' ? credits(clean(line)[0]) : [],
    explicit: isExplicit(renderer.badges),
  };
}

/** The top result's own card: an artist, album, playlist, track or video. */
export function topResult(card: Json, blockId: string): MetadataItem | undefined {
  const title = runsText(card?.title);
  if (!title) return undefined;
  const raw = thumbnailUrl(card.thumbnail);
  const watch = card.onTap?.watchEndpoint;
  const segments = splitBySeparator(card.subtitle?.runs ?? []).filter((segment) => segment.length > 0);
  if (watch?.videoId) {
    const hasVideo = isVideoType(musicVideoType(card.onTap));
    const entity: EntityRef = { kind: hasVideo ? 'MUSIC_VIDEO' : 'TRACK', providerId: watch.videoId };
    const artists = credits(segments[1]);
    const albumRun = segments[2]?.[0];
    const albumRef = browseRef(albumRun?.navigationEndpoint?.browseEndpoint);
    const durationSeconds = parseTime(segmentText(segments[segments.length - 1] ?? []));
    const artwork = raw ? { url: musicThumbnail(watch.videoId, raw, CARD_ARTWORK_SIZE) } : undefined;
    const explicit = isExplicit(card.subtitleBadges);
    return {
      id: `${blockId}#${entity.providerId}`,
      entity,
      title,
      subtitle: runsText(card.subtitle),
      artwork,
      view: viewOf(entity),
      artists,
      durationSeconds,
      explicit,
      track: {
        ref: entity,
        title,
        artists,
        album: albumRef?.kind === 'ALBUM' ? albumRun.text : undefined,
        albumRef: albumRef?.kind === 'ALBUM' ? albumRef : undefined,
        durationMs: durationSeconds !== undefined ? durationSeconds * 1000 : undefined,
        explicit,
        artwork,
        hasVideo,
        ids: { ytm: watch.videoId },
      },
    };
  }
  const entity = browseRef(card.onTap?.browseEndpoint);
  if (!entity) return undefined;
  return {
    id: `${blockId}#${entity.providerId}`,
    entity,
    title,
    subtitle: runsText(card.subtitle),
    artwork: raw ? { url: musicThumbnail('', raw, CARD_ARTWORK_SIZE) } : undefined,
    view: viewOf(entity),
    artists: entity.kind === 'ALBUM' ? credits(segments[1]) : [],
    explicit: isExplicit(card.subtitleBadges),
  };
}
