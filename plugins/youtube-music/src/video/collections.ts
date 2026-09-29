// Channel and playlist pages, and their videos as queue tracks for "play all".
import type { EntityRef, MetadataPage, PageRequest, TrackList, TracksRequest } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import * as api from './api';
import {
  aboutContinuation,
  CHANNEL_TABS,
  type ChannelTab,
  channelFilters,
  channelHeader,
  channelOwner,
  channelPage,
  isChannelTab,
  mapChannelTab,
  type TabPage,
  tabBlock,
  withAbout,
} from './channel';
import { decodeCursor, encodeCursor } from './cursor';
import type { Owner, VideoEntry } from './entries';
import { isVideo, playlistRef, toTrack } from './items';
import { isMix, mapMix, mapPlaylistVideos, mixHeader, playlistBlock, playlistHeader, playlistRows } from './playlist';

interface ChannelCursor {
  tab: ChannelTab;
  token: string;
  name?: string;
}

interface PlaylistCursor {
  token: string;
  offset: number;
}

interface MixCursor {
  after: string;
  /** What the mix already gave: the watch page rebuilds the mix around a video, repeating earlier ones. */
  seen: string[];
}

const MIX_SEEN_KEPT = 200;

const playlistId = (entity: EntityRef) => entity.providerId.replace(/^VL/, '');

const channelCursor = (tab: ChannelTab, token: string | undefined, name: string | undefined) =>
  token ? encodeCursor<ChannelCursor>({ tab, token, name }) : undefined;

const playlistCursor = (token: string | undefined, offset: number) =>
  token ? encodeCursor<PlaylistCursor>({ token, offset }) : undefined;

/** Only what can play now goes in a queue: a scheduled stream has nothing to play yet. */
const playable = (videos: VideoEntry[]) => videos.filter((video) => !video.upcoming).map(toTrack);

async function channelBrowse(channelId: string, tab?: ChannelTab): Promise<Json> {
  const response = await api.browse(channelId, tab ? CHANNEL_TABS[tab].params : undefined);
  if (!response?.contents && !response?.header) fail('NOT_FOUND', `No channel ${channelId}`);
  return response;
}

/** A tab's first page, or the page a cursor continues to, with the channel its items belong to. */
async function channelTabPage(channelId: string, tab: ChannelTab, cursor?: ChannelCursor): Promise<{ response?: Json; owner: Owner; page: TabPage }> {
  if (cursor) {
    const owner = { id: channelId, name: cursor.name };
    return { owner, page: mapChannelTab(await api.browseContinuation(cursor.token), owner) };
  }
  const response = await channelBrowse(channelId, tab);
  const owner = channelOwner(response, channelId);
  return { response, owner, page: mapChannelTab(response, owner) };
}

async function channelEntity({ entity, filterId, cursor }: PageRequest): Promise<MetadataPage> {
  const channelId = entity.providerId;
  const state = cursor ? decodeCursor<ChannelCursor>(cursor, 'token') : undefined;
  const tab: ChannelTab = state?.tab ?? (isChannelTab(filterId) ? filterId : 'videos');
  const pageId = `youtube/channel/${channelId}/${tab}`;

  if (tab === 'about') {
    const response = await channelBrowse(channelId);
    const filters = channelFilters(response);
    const header = channelHeader(response, channelId, filters.some((option) => option.id === 'videos'));
    const token = aboutContinuation(response);
    return channelPage(pageId, [token ? withAbout(header, await api.browseContinuation(token)) : header], filters);
  }

  const { response, owner, page } = await channelTabPage(channelId, tab, state);
  const next = channelCursor(tab, page.next, owner.name);
  if (!response) return channelPage(pageId, [tabBlock(tab, page.entries)], undefined, next);
  const filters = channelFilters(response);
  const header = channelHeader(response, channelId, filters.some((option) => option.id === 'videos'));
  return channelPage(pageId, [header, tabBlock(tab, page.entries)], filters, next);
}

async function playlistEntity({ entity, cursor }: PageRequest): Promise<MetadataPage> {
  const id = playlistId(entity);
  const pageId = `youtube/playlist/${id}`;
  if (isMix(id)) {
    const mix = mapMix(await api.next({ playlistId: id }));
    if (mix.videos.length === 0) fail('NOT_FOUND', `No mix ${id}`);
    return { id: pageId, blocks: [mixHeader(id, mix.title, mix.owner, mix.videos[0]), playlistBlock(playlistRows(mix.videos, 0))] };
  }
  if (cursor) {
    const state = decodeCursor<PlaylistCursor>(cursor, 'token');
    const page = mapPlaylistVideos(await api.browseContinuation(state.token));
    return {
      id: pageId,
      blocks: [playlistBlock(playlistRows(page.videos, state.offset))],
      nextCursor: playlistCursor(page.next, state.offset + page.videos.length),
    };
  }
  const response = await api.browse(`VL${id}`);
  const header = playlistHeader(response, id);
  const page = mapPlaylistVideos(response);
  if (!header.title && page.videos.length === 0) fail('NOT_FOUND', `No playlist ${id}`);
  return {
    id: pageId,
    blocks: [header, playlistBlock(playlistRows(page.videos, 0))],
    nextCursor: playlistCursor(page.next, page.videos.length),
  };
}

export async function entity(request: PageRequest): Promise<MetadataPage> {
  switch (request.entity.kind) {
    case 'CHANNEL':
      return channelEntity(request);
    case 'PLAYLIST':
      return playlistEntity(request);
    default:
      return fail('UNSUPPORTED', `No ${request.entity.kind} pages`);
  }
}

/** A mix goes on for as long as it is asked: each batch is the mix rebuilt after the last video, minus repeats. */
async function mixTracks(id: string, cursor: string | null | undefined): Promise<TrackList> {
  const state = cursor ? decodeCursor<MixCursor>(cursor, 'after') : undefined;
  const mix = mapMix(await api.next(state ? { videoId: state.after, playlistId: id } : { playlistId: id }));
  const seen = new Set(state ? [...state.seen, state.after] : []);
  const videos = mix.videos.filter((video) => !seen.has(video.id));
  const last = videos[videos.length - 1];
  const next = last ? encodeCursor<MixCursor>({ after: last.id, seen: [...seen, ...videos.map((video) => video.id)].slice(-MIX_SEEN_KEPT) }) : undefined;
  return { tracks: playable(videos), next, source: playlistRef(id) };
}

async function playlistTracks(id: string, cursor: string | null | undefined): Promise<TrackList> {
  if (isMix(id)) return mixTracks(id, cursor);
  const state = cursor ? decodeCursor<PlaylistCursor>(cursor, 'token') : undefined;
  const page = mapPlaylistVideos(state ? await api.browseContinuation(state.token) : await api.browse(`VL${id}`));
  const offset = (state?.offset ?? 0) + page.videos.length;
  return { tracks: playable(page.videos), next: playlistCursor(page.next, offset), source: playlistRef(id) };
}

/** A channel's uploads for "play all", from its Videos tab in the channel's own order (newest first). */
async function channelTracks(channelId: string, cursor: string | null | undefined): Promise<TrackList> {
  const state = cursor ? decodeCursor<ChannelCursor>(cursor, 'token') : undefined;
  const { owner, page } = await channelTabPage(channelId, 'videos', state);
  return {
    tracks: playable(page.entries.filter(isVideo)),
    next: channelCursor('videos', page.next, owner.name),
    source: { kind: 'CHANNEL', providerId: channelId },
  };
}

export async function tracks({ entity, cursor }: TracksRequest): Promise<TrackList> {
  switch (entity.kind) {
    case 'PLAYLIST':
      return playlistTracks(playlistId(entity), cursor);
    case 'CHANNEL':
      return channelTracks(entity.providerId, cursor);
    default:
      return fail('UNSUPPORTED', `No tracks for ${entity.kind}`);
  }
}
