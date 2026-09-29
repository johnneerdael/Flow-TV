// What a video carries beside its streams: chapters and the watch page's labels (one InnerTube `next`
// call, as the app's VideoChaptersParser and PlayerSecondaryMetadataLoader read it), and SponsorBlock's
// community-marked segments (sponsor.ajay.app, as SponsorBlockRepository asked for them).
import type { Artwork, Chapter, SkipSegment } from '@milkbeat/plugin-sdk';
import { mb } from '@milkbeat/plugin-sdk';
import { WEB } from '../innertube/clients';
import { innertube } from '../innertube/request';
import { artwork, dig, type Json, query, text } from '../util';

const CHAPTERS_PANEL = 'engagement-panel-macro-markers-description-chapters';
const SPONSOR_CATEGORIES = ['sponsor', 'intro', 'outro', 'selfpromo', 'interaction', 'music_offtopic'];
const SIDE_CALL_TIMEOUT_MS = 6_000;

export interface WatchExtras {
  chapters: Chapter[];
  viewsLabel?: string;
  publishedLabel?: string;
  channelAvatar?: Artwork;
}

/** Chapters from the watch response's macro-markers panel, by their exact start seconds. */
export function parseChapters(watchNext: Json): Chapter[] {
  const panels: Json[] = Array.isArray(watchNext?.engagementPanels) ? watchNext.engagementPanels : [];
  const panel = panels
    .map((entry) => entry?.engagementPanelSectionListRenderer)
    .find((renderer) => renderer?.panelIdentifier === CHAPTERS_PANEL);
  const items: Json[] = dig(panel, 'content', 'macroMarkersListRenderer', 'contents') ?? [];
  const chapters: Chapter[] = [];
  for (const item of items) {
    const marker = item?.macroMarkersListItemRenderer;
    const title = text(marker?.title).trim();
    const start = Number(dig(marker, 'onTap', 'watchEndpoint', 'startTimeSeconds'));
    if (title && Number.isInteger(start) && start >= 0) chapters.push({ title, startMs: start * 1000 });
  }
  return chapters.sort((a, b) => a.startMs - b.startMs);
}

/** The watch page's view count, publish date and channel avatar, already in the listener's language. */
export function parseWatchLabels(watchNext: Json): Omit<WatchExtras, 'chapters'> {
  const contents: Json[] = dig(watchNext, 'contents', 'twoColumnWatchNextResults', 'results', 'results', 'contents') ?? [];
  const primary = contents.find((entry) => entry?.videoPrimaryInfoRenderer)?.videoPrimaryInfoRenderer;
  const secondary = contents.find((entry) => entry?.videoSecondaryInfoRenderer)?.videoSecondaryInfoRenderer;
  const views = dig(primary, 'viewCount', 'videoViewCountRenderer');
  return {
    viewsLabel: text(views?.viewCount) || text(views?.shortViewCount) || undefined,
    publishedLabel: text(primary?.relativeDateText) || text(primary?.dateText) || undefined,
    channelAvatar: artwork(dig(secondary, 'owner', 'videoOwnerRenderer', 'thumbnail')),
  };
}

export async function watchExtras(videoId: string): Promise<WatchExtras> {
  try {
    const response = await innertube('next', { client: WEB, site: 'www', body: { videoId }, timeoutMs: SIDE_CALL_TIMEOUT_MS });
    return { chapters: parseChapters(response), ...parseWatchLabels(response) };
  } catch {
    return { chapters: [] };
  }
}

/** SponsorBlock's answer as skip segments; anything malformed is dropped. */
export function parseSkipSegments(body: string): SkipSegment[] {
  let entries: Json[];
  try {
    entries = JSON.parse(body);
  } catch {
    return [];
  }
  if (!Array.isArray(entries)) return [];
  const segments: SkipSegment[] = [];
  for (const entry of entries) {
    const [start, end] = Array.isArray(entry?.segment) ? entry.segment.map(Number) : [];
    if (typeof entry?.category !== 'string' || !Number.isFinite(start) || !Number.isFinite(end) || end <= start) continue;
    segments.push({ startMs: Math.round(start * 1000), endMs: Math.round(end * 1000), category: entry.category });
  }
  return segments.sort((a, b) => a.startMs - b.startMs);
}

export async function skipSegments(videoId: string): Promise<SkipSegment[]> {
  try {
    const response = await mb.http.fetch({
      url: `https://sponsor.ajay.app/api/skipSegments?${query({ videoID: videoId, categories: JSON.stringify(SPONSOR_CATEGORIES) })}`,
      timeoutMs: SIDE_CALL_TIMEOUT_MS,
    });
    return response.status === 200 ? parseSkipSegments(response.body) : [];
  } catch {
    return [];
  }
}
