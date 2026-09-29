// A video's watch page: its details and the related videos beside it, as the app's WatchMetadataResponse
// and getRelatedCandidates read them. Only videos are kept, since the host plays the lane as up-next.
import type { MetadataPage, PageBlockHeader } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import { toEntries } from './entries';
import { channelRef, collection, isVideo, toItem, videoRef } from './items';
import { channelIdOf, continuationToken, image, label, listContinuation, videoArtwork } from './json';

export const RELATED_BLOCK = 'related';

function watchContents(response: Json): Json[] {
  return response?.contents?.twoColumnWatchNextResults?.results?.results?.contents ?? [];
}

const rendererOf = (response: Json, key: string): Json => watchContents(response).find((entry) => entry[key])?.[key];

export function videoHeader(response: Json, videoId: string): PageBlockHeader | undefined {
  const primary = rendererOf(response, 'videoPrimaryInfoRenderer');
  const secondary = rendererOf(response, 'videoSecondaryInfoRenderer');
  const title = label(primary?.title);
  if (!title) return undefined;
  const count = primary?.viewCount?.videoViewCountRenderer;
  const owner = secondary?.owner?.videoOwnerRenderer;
  const ownerName = label(owner?.title);
  const ownerId = channelIdOf(owner?.navigationEndpoint) ?? channelIdOf(owner?.title);
  return {
    type: 'header',
    id: 'header',
    style: 'COVER',
    entity: videoRef(videoId),
    title,
    artwork: videoArtwork(videoId),
    details: [label(count?.viewCount) ?? label(count?.shortViewCount), label(primary?.dateText)].filter(
      (text): text is string => !!text,
    ),
    attribution: ownerName
      ? { name: ownerName, avatar: image(owner?.thumbnail), entity: ownerId ? channelRef(ownerId) : undefined }
      : undefined,
    description: label(secondary?.attributedDescription) ?? label(secondary?.description),
  };
}

/** Related results on the first page, or a continuation's appended ones. */
function relatedEntries(response: Json): Json[] {
  const results: Json[] | undefined = response?.contents?.twoColumnWatchNextResults?.secondaryResults?.secondaryResults?.results;
  const list =
    results ??
    (response?.onResponseReceivedEndpoints ?? []).flatMap(
      (endpoint: Json) => endpoint.appendContinuationItemsAction?.continuationItems ?? [],
    );
  return list.flatMap((entry: Json) => entry.itemSectionRenderer?.contents ?? [entry]);
}

export function mapRelated(response: Json, videoId: string, pageId: string, first: boolean): MetadataPage {
  const entries = relatedEntries(response);
  const videos = toEntries(entries).filter((entry) => isVideo(entry) && entry.id !== videoId);
  const header = first ? videoHeader(response, videoId) : undefined;
  return {
    id: pageId,
    blocks: [...(header ? [header] : []), collection(RELATED_BLOCK, videos.map((video) => toItem(video)))],
    nextCursor: listContinuation(entries),
  };
}

/** The token that opens the comment section, which the page leaves as a placeholder until scrolled to. */
export function commentsToken(response: Json): string | undefined {
  const section = watchContents(response).find(
    (entry) => entry.itemSectionRenderer?.sectionIdentifier === 'comment-item-section',
  )?.itemSectionRenderer;
  return section ? continuationToken(section.contents) : undefined;
}

/** The chat's first token; a replay's chat needs another endpoint and is not offered. */
export function liveChatToken(response: Json): string | undefined {
  const chat = response?.contents?.twoColumnWatchNextResults?.conversationBar?.liveChatRenderer;
  if (!chat || chat.isReplay) return undefined;
  return (chat.continuations ?? []).map((entry: Json) => entry.reloadContinuationData?.continuation).find(Boolean);
}
