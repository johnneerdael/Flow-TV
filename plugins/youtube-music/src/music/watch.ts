// A watch queue (`next`) read as tracks, ported from the app's YouTube.next and
// NextPage.fromPlaylistPanelVideoRenderer: the panel's tracks in YouTube's order, its continuation,
// and the automix playlist YouTube names at its end.
import type { TrackDescriptor } from '@milkbeat/plugin-sdk';
import { type Json, dig } from '../util';
import type { WatchEndpoint } from './api';
import { musicThumbnail } from './artwork';
import { browseRef, firstRun, isExplicit, isVideoType, lastUrl, musicVideoType, nextContinuation, oddElements, parseTime, splitBySeparator } from './renderers';

const QUEUE_ARTWORK_SIZE = 544;

export interface WatchPage {
  /** What the queue plays from, as "Battery Park Mix". */
  title?: string;
  tracks: TrackDescriptor[];
  continuation?: string;
  /** The automix playlist the queue continues into, when YouTube names one at its end. */
  automix?: WatchEndpoint;
}

export function panelTrack(renderer: Json): TrackDescriptor | undefined {
  const byline: Json[] | undefined = renderer?.longBylineText?.runs;
  const videoId: string | undefined = renderer?.videoId;
  const title = firstRun(renderer?.title);
  const durationSeconds = parseTime(firstRun(renderer?.lengthText));
  const thumbnail = lastUrl(renderer?.thumbnail?.thumbnails);
  if (!byline || !videoId || title === undefined || durationSeconds === undefined || !thumbnail) return undefined;
  const segments = splitBySeparator(byline);
  const albumRun = segments[1]?.[0]?.navigationEndpoint?.browseEndpoint ? segments[1][0] : undefined;
  const hasVideo = isVideoType(musicVideoType(renderer.navigationEndpoint));
  return {
    ref: { kind: hasVideo ? 'MUSIC_VIDEO' : 'TRACK', providerId: videoId },
    title,
    artists: oddElements(segments[0] ?? []).map((run) => {
      const id: string | undefined = run?.navigationEndpoint?.browseEndpoint?.browseId;
      return id ? { name: run.text, entity: { kind: 'ARTIST' as const, providerId: id } } : { name: run.text };
    }),
    album: albumRun?.text,
    albumRef: albumRun ? (browseRef(albumRun.navigationEndpoint.browseEndpoint) ?? { kind: 'ALBUM', providerId: albumRun.navigationEndpoint.browseEndpoint.browseId }) : undefined,
    durationMs: durationSeconds * 1000,
    explicit: isExplicit(renderer.badges),
    artwork: { url: musicThumbnail(videoId, thumbnail, QUEUE_ARTWORK_SIZE) },
    hasVideo,
    ids: { ytm: videoId },
  };
}

function endpointOf(watch: Json): WatchEndpoint {
  return { videoId: watch.videoId, playlistId: watch.playlistId, playlistSetVideoId: watch.playlistSetVideoId, index: watch.index, params: watch.params };
}

/** The queue panel of a `next` response, first page or continuation. */
export function watchPage(response: Json): WatchPage | undefined {
  const queue = dig(response, 'contents', 'singleColumnMusicWatchNextResultsRenderer', 'tabbedRenderer', 'watchNextTabbedResultsRenderer', 'tabs', 0, 'tabRenderer', 'content', 'musicQueueRenderer');
  const panel = response?.continuationContents?.playlistPanelContinuation ?? queue?.content?.playlistPanelRenderer;
  if (!panel) return undefined;
  const contents: Json[] = Array.isArray(panel.contents) ? panel.contents : [];
  const tracks = contents
    .map((content) => content?.playlistPanelVideoRenderer ?? content?.playlistPanelVideoWrapperRenderer?.primaryRenderer?.playlistPanelVideoRenderer)
    .map(panelTrack)
    .filter((track): track is TrackDescriptor => track !== undefined);
  const automix = dig(contents[contents.length - 1], 'automixPreviewVideoRenderer', 'content', 'automixPlaylistVideoRenderer', 'navigationEndpoint', 'watchPlaylistEndpoint');
  const title = firstRun(dig(queue, 'header', 'musicQueueHeaderRenderer', 'subtitle'));
  return { title, tracks, continuation: nextContinuation(panel.continuations), automix: automix ? endpointOf(automix) : undefined };
}
