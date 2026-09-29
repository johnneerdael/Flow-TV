// Live chat batches, as the app's LiveChatRepository read `get_live_chat`: text messages, Super Chats and
// memberships, and the wait the continuation asks for before the next poll.
import type { LiveChatBatch, LiveChatMessage } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import { image, label } from './json';

const MIN_POLL_MS = 1_000;
const MAX_POLL_MS = 30_000;
const DEFAULT_POLL_MS = 5_000;

/** A run's text; a standard emoji is its own character, a channel's custom one its shortcut. */
function runText(run: Json): string {
  if (typeof run?.text === 'string') return run.text;
  const emoji = run?.emoji;
  if (!emoji) return '';
  if (!emoji.isCustomEmoji && emoji.emojiId) return emoji.emojiId;
  return emoji.shortcuts?.[0] ?? emoji.emojiId ?? '';
}

const messageText = (message: Json): string => (message?.runs ?? []).map(runText).join('') || label(message) || '';

function toMessage(item: Json): LiveChatMessage | undefined {
  const text = item.liveChatTextMessageRenderer;
  const paid = item.liveChatPaidMessageRenderer;
  const member = item.liveChatMembershipItemRenderer;
  const renderer = text ?? paid ?? member;
  if (!renderer?.id) return undefined;
  const body = messageText(renderer.message) || (member ? messageText(renderer.headerSubtext) : '');
  return {
    id: renderer.id,
    author: label(renderer.authorName) ?? '',
    authorAvatar: image(renderer.authorPhoto),
    text: body,
    highlight: paid
      ? label(paid.purchaseAmountText)
      : member
        ? (label(member.headerPrimaryText) ?? label(member.headerSubtext))
        : undefined,
  };
}

export function mapLiveChat(response: Json): LiveChatBatch {
  const chat = response?.continuationContents?.liveChatContinuation;
  const continuation = (chat?.continuations ?? [])
    .map((entry: Json) => entry.timedContinuationData ?? entry.invalidationContinuationData ?? entry.reloadContinuationData)
    .find(Boolean);
  const messages = (chat?.actions ?? [])
    .map((action: Json) => action.addChatItemAction?.item)
    .filter(Boolean)
    .map(toMessage)
    .filter((message: LiveChatMessage | undefined): message is LiveChatMessage => !!message);
  const timeout = Number(continuation?.timeoutMs);
  return {
    messages,
    next: continuation?.continuation,
    pollAfterMs: Number.isFinite(timeout) && timeout > 0 ? Math.min(Math.max(timeout, MIN_POLL_MS), MAX_POLL_MS) : DEFAULT_POLL_MS,
  };
}
