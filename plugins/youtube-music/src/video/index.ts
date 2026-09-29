// The video role's pages: YouTube search, channels, playlists, related videos, comments, live chat.
import type { CommentsPage, CommentsRequest, EntityRef, PluginDefinition } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import * as api from './api';
import { entity, tracks } from './collections';
import { type CommentSort, mapComments } from './comments';
import { mapLiveChat } from './chat';
import { mapSearch, SEARCH_FILTERS } from './search';
import { mapSuggestions } from './suggest';
import { commentsToken, liveChatToken, mapRelated } from './watch';

function videoId(entity: EntityRef): string {
  if (entity.kind !== 'VIDEO') fail('UNSUPPORTED', `Expected a video, got ${entity.kind}`);
  return entity.providerId;
}

/** Each video's comment orders, so switching between them costs one request rather than three. */
const commentSorts = new Map<string, CommentSort[]>();
const COMMENT_SORTS_KEPT = 20;

function rememberSorts(id: string, sorts: CommentSort[]): void {
  if (sorts.length === 0) return;
  commentSorts.delete(id);
  commentSorts.set(id, sorts);
  if (commentSorts.size > COMMENT_SORTS_KEPT) commentSorts.delete(commentSorts.keys().next().value as string);
}

async function comments({ entity, sortId, cursor }: CommentsRequest): Promise<CommentsPage> {
  const id = videoId(entity);
  if (cursor) return mapComments(await api.next({ continuation: cursor })).page;

  const known = sortId ? commentSorts.get(id)?.find((sort) => sort.id === sortId) : undefined;
  if (known) {
    const sorted = mapComments(await api.next({ continuation: known.token }));
    rememberSorts(id, sorted.sortTokens);
    return sorted.page;
  }

  const token = commentsToken(await api.next({ videoId: id }));
  if (!token) return { comments: [], sorts: [] };
  const first = mapComments(await api.next({ continuation: token }));
  rememberSorts(id, first.sortTokens);
  const wanted = sortId ? first.sortTokens.find((sort) => sort.id === sortId) : undefined;
  if (!wanted || wanted === first.sortTokens[0]) return first.page;
  return mapComments(await api.next({ continuation: wanted.token })).page;
}

export const videoPages: NonNullable<PluginDefinition['video']> = {
  async search({ query, filterId, cursor }) {
    const filter = filterId ? SEARCH_FILTERS.find((option) => option.id === filterId) : undefined;
    if (filterId && !filter) fail('NOT_FOUND', `No search filter ${filterId}`);
    const pageId = `youtube/search/${filter?.id ?? 'all'}/${query}`;
    const response = cursor ? await api.search(query, undefined, cursor) : await api.search(query, filter?.params);
    return mapSearch(response, pageId, !cursor);
  },

  async suggest({ query }) {
    return query.trim() ? mapSuggestions(await api.suggestions(query)) : { queries: [] };
  },

  entity,
  tracks,

  async related({ entity, cursor }) {
    const id = videoId(entity);
    const pageId = `youtube/related/${id}`;
    const response = await api.next(cursor ? { continuation: cursor } : { videoId: id });
    return mapRelated(response, id, pageId, !cursor);
  },

  comments,

  async liveChat({ entity, cursor }) {
    const id = videoId(entity);
    const token = cursor ?? liveChatToken(await api.next({ videoId: id }));
    if (!token) fail('UNAVAILABLE', `${id} has no live chat`, { userMessage: 'This video has no live chat' });
    return mapLiveChat(await api.liveChat(token));
  },
};
