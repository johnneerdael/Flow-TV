// Reading YouTube Music's renderers: the small facts every mapper needs (what a browse endpoint opens,
// a thumbnail's URL, a watch endpoint's video type, continuation tokens), ported from the app's
// innertube models so every page reads them the same way.
import type { EntityKind, EntityRef } from '@milkbeat/plugin-sdk';
import { type Json, dig, text } from '../util';

/** An audio track's "video": a still cover. Every other music video type has pictures. */
export const MUSIC_VIDEO_TYPE_ATV = 'MUSIC_VIDEO_TYPE_ATV';

/** The text of runs, or undefined when it is blank. */
export function runsText(value: Json): string | undefined {
  const joined = text(value);
  return joined.trim() ? joined : undefined;
}

/** The first run's text, as the app's parsers read titles. */
export function firstRun(value: Json): string | undefined {
  const first = dig(value, 'runs', 0, 'text');
  return typeof first === 'string' ? first : undefined;
}

/** The last thumbnail's URL of a thumbnail renderer (music or cropped square), as the app reads it. */
export function thumbnailUrl(renderer: Json): string | undefined {
  const thumbnails = (renderer?.musicThumbnailRenderer ?? renderer?.croppedSquareThumbnailRenderer)?.thumbnail?.thumbnails;
  return lastUrl(thumbnails);
}

export function lastUrl(thumbnails: Json): string | undefined {
  if (!Array.isArray(thumbnails) || thumbnails.length === 0) return undefined;
  const url = thumbnails[thumbnails.length - 1]?.url;
  if (typeof url !== 'string' || !url) return undefined;
  return url.startsWith('//') ? `https:${url}` : url;
}

export function pageType(browseEndpoint: Json): string | undefined {
  return dig(browseEndpoint, 'browseEndpointContextSupportedConfigs', 'browseEndpointContextMusicConfig', 'pageType');
}

const PAGE_KINDS: Record<string, EntityKind> = {
  MUSIC_PAGE_TYPE_ARTIST: 'ARTIST',
  MUSIC_PAGE_TYPE_USER_CHANNEL: 'PROFILE',
  MUSIC_PAGE_TYPE_ALBUM: 'ALBUM',
  MUSIC_PAGE_TYPE_AUDIOBOOK: 'ALBUM',
  MUSIC_PAGE_TYPE_PLAYLIST: 'PLAYLIST',
};

/** What a browse endpoint opens; a playlist is known by its bare id, not its VL browse id. */
export function browseRef(browseEndpoint: Json): EntityRef | undefined {
  const browseId: unknown = browseEndpoint?.browseId;
  const kind = PAGE_KINDS[pageType(browseEndpoint) ?? ''];
  if (!kind || typeof browseId !== 'string') return undefined;
  return { kind, providerId: kind === 'PLAYLIST' ? stripVl(browseId) : browseId };
}

export function stripVl(browseId: string): string {
  return browseId.startsWith('VL') ? browseId.slice(2) : browseId;
}

/** A watch or watch-playlist endpoint, whichever the navigation endpoint holds. */
export function anyWatchEndpoint(navigationEndpoint: Json): Json {
  return navigationEndpoint?.watchEndpoint ?? navigationEndpoint?.watchPlaylistEndpoint;
}

export function musicVideoType(navigationEndpoint: Json): string | undefined {
  return dig(anyWatchEndpoint(navigationEndpoint), 'watchEndpointMusicSupportedConfigs', 'watchEndpointMusicConfig', 'musicVideoType');
}

/** Whether a track of [videoType] has pictures: official and user videos do, audio tracks do not. */
export function isVideoType(videoType: string | undefined): boolean {
  return videoType !== undefined && videoType !== MUSIC_VIDEO_TYPE_ATV;
}

/** The play button over a card or row's thumbnail. */
export function playEndpoint(overlay: Json): Json {
  return dig(overlay, 'musicItemThumbnailOverlayRenderer', 'content', 'musicPlayButtonRenderer', 'playNavigationEndpoint');
}

export function isExplicit(badges: Json): boolean {
  return Array.isArray(badges) && badges.some((badge) => dig(badge, 'musicInlineBadgeRenderer', 'icon', 'iconType') === 'MUSIC_EXPLICIT_BADGE');
}

/** The watch-playlist endpoint of the menu item with [iconType], e.g. MUSIC_SHUFFLE or MIX. */
export function menuEndpoint(menu: Json, iconType: string): Json {
  const items: Json[] = dig(menu, 'menuRenderer', 'items') ?? [];
  const item = items.find((entry) => dig(entry, 'menuNavigationItemRenderer', 'icon', 'iconType') === iconType);
  return dig(item, 'menuNavigationItemRenderer', 'navigationEndpoint', 'watchPlaylistEndpoint');
}

/** "3:25" or "1:02:03" in seconds. */
export function parseTime(value: string | undefined): number | undefined {
  if (!value) return undefined;
  const parts = value.split(':');
  if (parts.length !== 2 && parts.length !== 3) return undefined;
  const numbers = parts.map((part) => (/^[+-]?\d+$/.test(part) ? Number(part) : Number.NaN));
  if (numbers.some(Number.isNaN)) return undefined;
  return numbers.length === 2 ? numbers[0] * 60 + numbers[1] : numbers[0] * 3600 + numbers[1] * 60 + numbers[2];
}

/** The token of the continuation item among a shelf's contents. */
export function itemContinuation(contents: Json): string | undefined {
  if (!Array.isArray(contents)) return undefined;
  const item = contents.find((content) => content?.continuationItemRenderer);
  return dig(item, 'continuationItemRenderer', 'continuationEndpoint', 'continuationCommand', 'token') ?? undefined;
}

/** The token of a renderer's `continuations`, as next or radio continuation data. */
export function nextContinuation(continuations: Json): string | undefined {
  const first = Array.isArray(continuations) ? continuations[0] : undefined;
  return (first?.nextContinuationData ?? first?.nextRadioContinuationData)?.continuation ?? undefined;
}

/** Runs split at the " • " separators. */
export function splitBySeparator(runs: Json[]): Json[][] {
  const result: Json[][] = [[]];
  for (const run of runs) {
    if (run?.text === ' • ') result.push([]);
    else result[result.length - 1].push(run);
  }
  return result;
}

/** Every other run, starting with the first: the names between " & " and ", " joiners. */
export function oddElements<T>(runs: T[]): T[] {
  return runs.filter((_, index) => index % 2 === 0);
}

/** A flex or fixed column's runs; fixed columns name their renderer differently. */
export function columnRuns(column: Json): Json[] {
  const renderer = column?.musicResponsiveListItemFlexColumnRenderer ?? column?.musicResponsiveListItemFixedColumnRenderer;
  const runs = renderer?.text?.runs;
  return Array.isArray(runs) ? runs : [];
}

export function columnText(column: Json): string | undefined {
  const renderer = column?.musicResponsiveListItemFlexColumnRenderer ?? column?.musicResponsiveListItemFixedColumnRenderer;
  return runsText(renderer?.text);
}
