// Video playlists, as the app's VideoPlaylistPage read the WEB `VL<id>` browse: a header, then a hundred
// videos a page. Radio mixes (`RD…`) have no browse page; they are read from the watch page's queue.
import type { MetadataItem, PageBlockHeader } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import { type Owner, toEntries, type VideoEntry } from './entries';
import { channelRef, collection, isVideo, playlistRef, toItem } from './items';
import { find, image, label, listContinuation, walk } from './json';

export const TRACKS_BLOCK = 'tracks';

/** A generated radio mix, which only the watch page can read. YouTube Music's `RDCLAK` albums browse normally. */
export const isMix = (playlistId: string): boolean => playlistId.startsWith('RD') && !playlistId.startsWith('RDCLAK');

/** The items of a first page's video list, or of a continuation's append action. */
function playlistEntries(response: Json): Json[] {
  const actions: Json[] | undefined = response?.onResponseReceivedActions;
  if (Array.isArray(actions)) return actions.flatMap((action) => action.appendContinuationItemsAction?.continuationItems ?? []);
  let list: Json[] = [];
  walk(response?.contents, (object) => {
    const contents = object.itemSectionRenderer?.contents ?? object.playlistVideoListRenderer?.contents;
    if (Array.isArray(contents) && contents.some((entry: Json) => entry.lockupViewModel || entry.playlistVideoRenderer)) {
      list = contents;
      return true;
    }
    return false;
  });
  return list;
}

export interface PlaylistVideos {
  videos: VideoEntry[];
  next?: string;
}

export function mapPlaylistVideos(response: Json): PlaylistVideos {
  const entries = playlistEntries(response);
  return { videos: toEntries(entries).filter(isVideo), next: listContinuation(entries) };
}

/** "by Name" in the header's avatar stack, linked to the owner's channel. */
function headerOwner(page: Json): Owner | undefined {
  const stack = find(page?.metadata, 'avatarStackViewModel');
  const name = label(stack?.text)?.replace(/^by\s+/i, '');
  if (!name) return undefined;
  const id = find(stack.text, 'browseEndpoint')?.browseId;
  return { name, id: typeof id === 'string' ? id : undefined, avatar: image(find(stack, 'avatarViewModel')?.image) };
}

export function playlistHeader(response: Json, playlistId: string): PageBlockHeader {
  const page = find(response?.header, 'pageHeaderViewModel');
  const sidebar = find(response?.sidebar, 'playlistSidebarPrimaryInfoRenderer');
  const owner = headerOwner(page);
  const rows: Json[] = page?.metadata?.contentMetadataViewModel?.metadataRows ?? [];
  const details = rows
    .flatMap((row) => (row.metadataParts ?? []).filter((part: Json) => !part.avatarStack).map((part: Json) => label(part.text)))
    .filter((text): text is string => !!text && !/^playlist$/i.test(text));
  return {
    type: 'header',
    id: 'header',
    style: 'COVER',
    entity: playlistRef(playlistId),
    title:
      label(find(response?.header, 'pageHeaderRenderer')?.pageTitle) ??
      label(page?.title?.dynamicTextViewModel?.text) ??
      label(sidebar?.title) ??
      '',
    artwork:
      image(find(page?.heroImage, 'image')) ??
      image(find(sidebar?.thumbnailRenderer, 'thumbnail')) ??
      image(response?.microformat?.microformatDataRenderer?.thumbnail),
    details,
    attribution: owner?.name
      ? { name: owner.name, avatar: owner.avatar, entity: owner.id ? channelRef(owner.id) : undefined }
      : undefined,
    description: label(page?.description?.descriptionPreviewViewModel?.description) ?? label(sidebar?.description),
    tracks: playlistRef(playlistId),
  };
}

/** Rows numbered from [offset], the count of videos on the pages before. */
export function playlistRows(videos: VideoEntry[], offset: number): MetadataItem[] {
  return videos.map((video, index) => ({ ...toItem(video), view: 'TRACK_ROW', ordinal: offset + index + 1 }));
}

export const playlistBlock = (items: MetadataItem[]) => collection(TRACKS_BLOCK, items, null, 'TRACK_TABLE');

/** A mix's queue on its watch page: the mix's title and its videos in order. */
export function mapMix(response: Json): { title?: string; owner?: string; videos: VideoEntry[] } {
  const panel = response?.contents?.twoColumnWatchNextResults?.playlist?.playlist;
  return {
    title: label(panel?.title) ?? label(panel?.titleText),
    owner: label(panel?.ownerName) ?? label(panel?.longBylineText),
    videos: toEntries(panel?.contents).filter(isVideo),
  };
}

export function mixHeader(playlistId: string, title: string | undefined, owner: string | undefined, first?: VideoEntry): PageBlockHeader {
  return {
    type: 'header',
    id: 'header',
    style: 'COVER',
    entity: playlistRef(playlistId),
    title: title ?? '',
    artwork: first?.artwork,
    details: owner ? [owner] : [],
    tracks: playlistRef(playlistId),
  };
}
