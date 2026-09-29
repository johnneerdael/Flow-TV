// Artist, album and playlist pages as YouTube Music serves them, ported from the app's
// YouTubePageMapper: an artist's portrait above its top songs and shelves; a collection's cover and
// details beside its tracks, then related shelves.
import type { ArtistCredit, Attribution, EntityRef, MetadataPage, PageBlock, PageBlockHeader } from '@milkbeat/plugin-sdk';
import { type Json, dig } from '../util';
import { anyWatchEndpoint, browseRef, itemContinuation, lastUrl, nextContinuation, runsText, thumbnailUrl } from './renderers';
import { type TrackTableOptions, carousel, trackTable, uniqueBlocks } from './shelves';

/** The id of the block holding a collection's tracks, which continuation pages extend. */
export const TRACKS_BLOCK_ID = 'tracks';

const artwork = (url: string | undefined) => (url ? { url } : undefined);
const firstTabSections = (contents: Json): Json[] =>
  dig(contents, 'tabs', 0, 'tabRenderer', 'content', 'sectionListRenderer', 'contents') ?? [];

function section(content: Json, options: TrackTableOptions = {}): PageBlock | undefined {
  if (content?.musicCarouselShelfRenderer) return carousel(content.musicCarouselShelfRenderer);
  if (content?.musicPlaylistShelfRenderer) return trackTable(TRACKS_BLOCK_ID, undefined, content.musicPlaylistShelfRenderer.contents ?? [], options);
  const shelf = content?.musicShelfRenderer;
  if (!shelf) return undefined;
  const title = runsText(shelf.title);
  const showAll = browseRef(shelf.bottomEndpoint?.browseEndpoint);
  return trackTable(title ?? TRACKS_BLOCK_ID, title, shelf.contents ?? [], {
    ...options,
    showAll: showAll?.kind === 'PLAYLIST' ? showAll : undefined,
  });
}

/** The header of an artist's page, or of a profile's, which YouTube draws another way. */
function portrait(response: Json, entity: EntityRef): PageBlockHeader | undefined {
  const header = response?.header?.musicImmersiveHeaderRenderer ?? response?.header?.musicVisualHeaderRenderer ?? response?.header?.musicHeaderRenderer;
  if (!header) return undefined;
  const station: string | undefined = anyWatchEndpoint(header.startRadioButton?.buttonRenderer?.navigationEndpoint)?.playlistId;
  const monthly = runsText(header.monthlyListenerCount);
  return {
    type: 'header',
    id: `${entity.providerId}/header`,
    style: 'PORTRAIT',
    entity,
    title: runsText(header.title) ?? '',
    artwork: artwork(thumbnailUrl(header.thumbnail) ?? thumbnailUrl(header.foregroundThumbnail)),
    details: monthly ? [monthly] : [],
    description: runsText(header.description),
    station: station ? { kind: 'RADIO', providerId: station } : undefined,
  };
}

/** An artist's page (or a profile's): its portrait, then its top songs and shelves in order. */
export function artistPage(response: Json, entity: EntityRef): MetadataPage {
  const sections = firstTabSections(response?.contents?.singleColumnBrowseResultsRenderer);
  return {
    id: `youtube-music/${entity.kind.toLowerCase()}/${entity.providerId}`,
    blocks: uniqueBlocks([portrait(response, entity), ...sections.map((content) => section(content))]),
  };
}

/** The header of a collection, whether the listener can edit it or not. */
export function collectionHeader(response: Json): Json {
  const sections = firstTabSections(response?.contents?.twoColumnBrowseResultsRenderer);
  for (const content of sections) {
    const header = content?.musicResponsiveHeaderRenderer ?? content?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer;
    if (header) return header;
  }
  return undefined;
}

function owner(stack: Json): Attribution | undefined {
  const name: string | undefined = stack?.text?.content;
  if (!name?.trim()) return undefined;
  return {
    name,
    avatar: artwork(lastUrl(dig(stack, 'avatars', 0, 'avatarViewModel', 'image', 'sources'))),
    entity: browseRef(dig(stack, 'rendererContext', 'commandContext', 'onTap', 'innertubeCommand', 'browseEndpoint')),
  };
}

/** The playlist holding a collection's tracks: its Play button's, or a playlist's own id. */
export function collectionPlaylistId(header: Json, entity: EntityRef): string | undefined {
  const buttons: Json[] = Array.isArray(header?.buttons) ? header.buttons : [];
  for (const button of buttons) {
    const id = anyWatchEndpoint(button?.musicPlayButtonRenderer?.playNavigationEndpoint)?.playlistId;
    if (id) return id;
  }
  return entity.kind === 'PLAYLIST' || entity.kind === 'MIX' ? entity.providerId : undefined;
}

export function cover(header: Json, entity: EntityRef): PageBlockHeader {
  const playlistId = collectionPlaylistId(header, entity);
  const artistRun = header.straplineTextOne?.runs?.[0];
  const details = [runsText(header.subtitle), runsText(header.secondSubtitle)].filter((it): it is string => it !== undefined);
  const attribution: Attribution | undefined = String(artistRun?.text ?? '').trim()
    ? {
        name: artistRun.text,
        avatar: artwork(thumbnailUrl(header.straplineThumbnail)),
        entity: browseRef(artistRun.navigationEndpoint?.browseEndpoint),
      }
    : owner(header.facepile?.avatarStackViewModel);
  return {
    type: 'header',
    id: `${entity.providerId}/header`,
    style: 'COVER',
    entity,
    title: runsText(header.title) ?? '',
    artwork: artwork(thumbnailUrl(header.thumbnail)),
    details,
    attribution,
    description: runsText(dig(header, 'description', 'musicDescriptionShelfRenderer', 'description')),
    tracks: playlistId ? { kind: 'PLAYLIST', providerId: playlistId } : undefined,
  };
}

/** The rows of a collection's tracks, and what they need from the page (album, artists, cover). */
export function collectionTracks(response: Json, entity: EntityRef): { contents: Json[]; options: TrackTableOptions; continuation?: string } {
  const header = collectionHeader(response);
  const head = header ? cover(header, entity) : undefined;
  const isAlbum = entity.kind === 'ALBUM';
  const albumArtists: ArtistCredit[] = isAlbum && head?.attribution ? [{ name: head.attribution.name, entity: head.attribution.entity }] : [];
  const sections: Json[] = dig(response, 'contents', 'twoColumnBrowseResultsRenderer', 'secondaryContents', 'sectionListRenderer', 'contents') ?? [];
  const shelf = sections.map((content) => content?.musicPlaylistShelfRenderer ?? content?.musicShelfRenderer).find(Boolean);
  return {
    contents: shelf?.contents ?? [],
    continuation: itemContinuation(shelf?.contents) ?? nextContinuation(shelf?.continuations),
    options: {
      numbered: isAlbum,
      defaultArtists: albumArtists,
      album: isAlbum && head ? { name: head.title, ref: entity } : undefined,
      fallbackArtwork: isAlbum ? (head?.artwork ?? undefined) : undefined,
    },
  };
}

/** An album or a playlist: its cover and details beside its tracks, then shelves like it. */
export function collectionPage(response: Json, entity: EntityRef): MetadataPage {
  const header = collectionHeader(response);
  const head = header ? cover(header, entity) : undefined;
  const { options } = collectionTracks(response, entity);
  const sections: Json[] = dig(response, 'contents', 'twoColumnBrowseResultsRenderer', 'secondaryContents', 'sectionListRenderer', 'contents') ?? [];
  const tracksShelf = sections.find((content) => content?.musicPlaylistShelfRenderer)?.musicPlaylistShelfRenderer;
  return {
    id: `youtube-music/${entity.kind.toLowerCase()}/${entity.providerId}`,
    blocks: uniqueBlocks([head, ...sections.map((content) => section(content, options))]),
    nextCursor: itemContinuation(tracksShelf?.contents) ?? nextContinuation(tracksShelf?.continuations),
  };
}

/** The rows of a collection's continuation, as served in either of YouTube's two shapes. */
export function continuationRows(response: Json): { contents: Json[]; continuation?: string } {
  const shelf = response?.continuationContents?.musicPlaylistShelfContinuation ?? response?.continuationContents?.musicShelfContinuation;
  const contents: Json[] = shelf?.contents ?? dig(response, 'onResponseReceivedActions', 0, 'appendContinuationItemsAction', 'continuationItems') ?? [];
  return { contents, continuation: itemContinuation(contents) ?? nextContinuation(shelf?.continuations) };
}

/** More tracks of a long playlist, as a page holding only the extended tracks block. */
export function tracksContinuationPage(response: Json): MetadataPage {
  const { contents, continuation } = continuationRows(response);
  return {
    id: TRACKS_BLOCK_ID,
    blocks: uniqueBlocks([trackTable(TRACKS_BLOCK_ID, undefined, contents)]),
    nextCursor: continuation,
  };
}
