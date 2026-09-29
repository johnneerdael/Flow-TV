// Comments, as the app's VideoCommentsPage and CommentsParsing read them: the thread list names entity
// keys and `frameworkUpdates` holds what they resolve to. One reader serves top-level pages and replies.
import type { Comment, CommentsPage, FilterOption } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import { continuationToken, label, leadingCount } from './json';

export interface CommentSort extends FilterOption {
  token: string;
}

/** Sort ids are positional, so a listener's choice carries across videos whatever the labels' language. */
const sortId = (index: number): string => (index === 0 ? 'top' : index === 1 ? 'newest' : `sort-${index}`);

function mutations(response: Json): Map<string, Json> {
  const map = new Map<string, Json>();
  for (const mutation of response?.frameworkUpdates?.entityBatchUpdate?.mutations ?? []) {
    if (typeof mutation?.entityKey === 'string') map.set(mutation.entityKey, mutation.payload);
  }
  return map;
}

/** The items of every reload and append command; a first page splits its header and threads across two. */
function items(response: Json): Json[] {
  return (response?.onResponseReceivedEndpoints ?? []).flatMap((endpoint: Json) => {
    const command = endpoint.reloadContinuationItemsCommand ?? endpoint.appendContinuationItemsAction;
    return command?.continuationItems ?? [];
  });
}

const count = (value: Json): number => {
  if (typeof value === 'number') return value;
  const text = label(value);
  if (!text) return 0;
  const scaled = text.match(/^([\d.,]+)\s*([KMB])$/i);
  if (scaled) return Math.round(Number(scaled[1].replace(',', '.')) * { K: 1e3, M: 1e6, B: 1e9 }[scaled[2].toUpperCase() as 'K']);
  return leadingCount(text) ?? 0;
};

function modernComment(view: Json, entities: Map<string, Json>, repliesCursor?: string, pinned = false): Comment | undefined {
  const model = view?.commentViewModel ?? view;
  const entity = entities.get(model?.commentKey)?.commentEntityPayload;
  const properties = entity?.properties;
  const id: string | undefined = properties?.commentId ?? model?.commentId;
  if (!properties || !id) return undefined;
  const author = entity.author ?? {};
  const toolbar = entity.toolbar ?? {};
  const avatar: string | undefined = author.avatarThumbnailUrl;
  return {
    id,
    author: author.displayName ?? '',
    authorAvatar: avatar ? { url: avatar } : undefined,
    text: label(properties.content) ?? '',
    publishedLabel: properties.publishedTime,
    likesLabel: label(toolbar.likeCountNotliked),
    replyCount: count(toolbar.replyCount),
    repliesCursor,
    pinned: pinned || !!label(model?.pinnedText) || !!label(properties.pinnedText),
    byCreator: author.isCreator === true,
  };
}

function legacyComment(renderer: Json, repliesCursor?: string): Comment | undefined {
  const id: string | undefined = renderer?.commentId;
  if (!id) return undefined;
  const avatar = renderer.authorThumbnail?.thumbnails?.slice(-1)[0]?.url;
  return {
    id,
    author: label(renderer.authorText) ?? '',
    authorAvatar: avatar ? { url: avatar } : undefined,
    text: label(renderer.contentText) ?? '',
    publishedLabel: label(renderer.publishedTimeText),
    likesLabel: label(renderer.voteCount),
    replyCount: count(renderer.replyCount),
    repliesCursor,
    pinned: !!renderer.pinnedCommentBadge,
    byCreator: !!renderer.authorIsChannelOwner,
  };
}

function sorts(header: Json): CommentSort[] {
  const entries: Json[] = header?.sortMenu?.sortFilterSubMenuRenderer?.subMenuItems ?? [];
  return entries
    .map((entry, index) => ({ id: sortId(index), label: label(entry.title) ?? '', token: entry.serviceEndpoint?.continuationCommand?.token }))
    .filter((sort): sort is CommentSort => typeof sort.token === 'string');
}

export interface MappedComments {
  page: CommentsPage;
  sortTokens: CommentSort[];
}

export function mapComments(response: Json): MappedComments {
  const entities = mutations(response);
  const comments: Comment[] = [];
  const seen = new Set<string>();
  let next: string | undefined;
  let header: Json;

  for (const item of items(response)) {
    if (item.commentsHeaderRenderer) {
      header = item.commentsHeaderRenderer;
      continue;
    }
    if (item.continuationItemRenderer) {
      next = next ?? continuationToken(item.continuationItemRenderer);
      continue;
    }
    const thread = item.commentThreadRenderer;
    const replies = thread?.replies?.commentRepliesRenderer;
    const repliesCursor = replies ? continuationToken(replies.contents) : undefined;
    const pinned = thread?.renderingPriority === 'RENDERING_PRIORITY_PINNED_COMMENT';
    const comment = thread
      ? (modernComment(thread.commentViewModel, entities, repliesCursor, pinned) ??
        legacyComment(thread.comment?.commentRenderer, repliesCursor))
      : item.commentViewModel
        ? modernComment(item.commentViewModel, entities)
        : legacyComment(item.commentRenderer);
    if (comment && !seen.has(comment.id)) {
      seen.add(comment.id);
      comments.push(comment);
    }
  }

  const sortTokens = sorts(header);
  return {
    page: {
      comments,
      next,
      sorts: sortTokens.map(({ id, label }) => ({ id, label })),
      totalLabel: label(header?.countText) ?? label(header?.commentsCount),
    },
    sortTokens,
  };
}
