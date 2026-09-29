// Every renderer the main site lists videos, channels and playlists in, read into one entry shape.
// Ported from the app's FeedItemParsers.kt: an unrecognised renderer yields nothing rather than failing
// the page it sits in. Shorts are left out: the TV has no vertical-video surface.
import type { Artwork } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import { channelIdOf, find, image, label, leadingCount, parseDuration, videoArtwork, walk } from './json';

export interface Owner {
  id?: string;
  name?: string;
  avatar?: Artwork;
}

export interface VideoEntry {
  kind: 'VIDEO';
  id: string;
  title: string;
  channel?: Owner;
  durationSeconds?: number;
  details: string[];
  live: boolean;
  upcoming: boolean;
  artwork: Artwork;
}

export interface ChannelEntry {
  kind: 'CHANNEL';
  id: string;
  title: string;
  details: string[];
  artwork?: Artwork;
}

export interface PlaylistEntry {
  kind: 'PLAYLIST';
  id: string;
  title: string;
  details: string[];
  artwork?: Artwork;
  channel?: Owner;
}

export type Entry = VideoEntry | ChannelEntry | PlaylistEntry;

type Parser = (node: Json, owner: Owner) => Entry | undefined;

const PARSERS: Record<string, Parser> = {
  videoRenderer: fromVideoRenderer,
  gridVideoRenderer: fromVideoRenderer,
  compactVideoRenderer: fromVideoRenderer,
  playlistPanelVideoRenderer: fromVideoRenderer,
  playlistVideoRenderer: fromVideoRenderer,
  lockupViewModel: fromLockup,
  channelRenderer: fromChannelRenderer,
  gridChannelRenderer: fromChannelRenderer,
  playlistRenderer: fromPlaylistRenderer,
  gridPlaylistRenderer: fromPlaylistRenderer,
};

/**
 * One list entry, bare or wrapped in a `richItemRenderer`. [owner] is the channel a channel tab's
 * entries belong to, since those carry no byline of their own.
 */
export function toEntry(node: Json, owner: Owner = {}): Entry | undefined {
  const content = node?.richItemRenderer?.content ?? node;
  if (!content || typeof content !== 'object') return undefined;
  for (const key of Object.keys(PARSERS)) {
    if (content[key]) return PARSERS[key](content[key], owner);
  }
  return undefined;
}

/** The entries of a list, each once. */
export function toEntries(nodes: Json[] | undefined, owner: Owner = {}): Entry[] {
  const seen = new Set<string>();
  const entries: Entry[] = [];
  for (const node of nodes ?? []) {
    const entry = toEntry(node, owner);
    if (entry && !seen.has(`${entry.kind}:${entry.id}`)) {
      seen.add(`${entry.kind}:${entry.id}`);
      entries.push(entry);
    }
  }
  return entries;
}

const mentionsWatching = (value: string | undefined) => /watching/i.test(value ?? '');
const mentionsViews = (value: string) => /view|watching/i.test(value);
const mentionsWaiting = (value: string) => /waiting/i.test(value);

function byline(node: Json): Owner | undefined {
  for (const key of ['ownerText', 'shortBylineText', 'longBylineText']) {
    const name = label(node[key]);
    if (name) return { name, id: channelIdOf(node[key]) };
  }
  return undefined;
}

function bylineAvatar(node: Json): Artwork | undefined {
  return (
    image(node.channelThumbnailSupportedRenderers?.channelThumbnailWithLinkRenderer?.thumbnail) ??
    image(node.channelThumbnail) ??
    image(find(node.avatar, 'avatarViewModel')?.image)
  );
}

function fromVideoRenderer(node: Json, owner: Owner): VideoEntry | undefined {
  const id: string | undefined = node.videoId;
  const title = label(node.title);
  if (!id || !title) return undefined;
  const views = label(node.viewCountText);
  const timeStatus = (node.thumbnailOverlays ?? []).find((overlay: Json) => overlay.thumbnailOverlayTimeStatusRenderer)
    ?.thumbnailOverlayTimeStatusRenderer?.style;
  const badges: Json[] = node.badges ?? [];
  const live =
    timeStatus === 'LIVE' ||
    badges.some((badge) => badge.metadataBadgeRenderer?.style === 'BADGE_STYLE_TYPE_LIVE_NOW') ||
    mentionsWatching(views);
  const startTime = Number(node.upcomingEventData?.startTime);
  const upcoming = !live && Number.isFinite(startTime) && startTime > 0;
  const found = byline(node);
  const details = live
    ? [views]
    : upcoming
      ? [scheduledLabel(node.upcomingEventData, startTime)]
      : [label(node.shortViewCountText) ?? views, label(node.publishedTimeText)];
  return {
    kind: 'VIDEO',
    id,
    title,
    channel: { id: found?.id ?? owner.id, name: found?.name ?? owner.name, avatar: bylineAvatar(node) ?? owner.avatar },
    durationSeconds: live ? undefined : parseDuration(label(node.lengthText)),
    details: details.filter((line): line is string => !!line),
    live,
    upcoming,
    artwork: videoArtwork(id),
  };
}

/** "Scheduled for …" with the date YouTube leaves as a placeholder for the client to fill in (UTC). */
function scheduledLabel(event: Json, startSeconds: number): string {
  const date = new Date(startSeconds * 1000);
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const pad = (value: number) => String(value).padStart(2, '0');
  const formatted = `${months[date.getUTCMonth()]} ${date.getUTCDate()}, ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())} UTC`;
  const template = label(event?.upcomingEventText);
  return template && template.indexOf('DATE_PLACEHOLDER') >= 0 ? template.replace('DATE_PLACEHOLDER', formatted) : formatted;
}

interface LockupPart {
  text: string;
  channelId?: string;
}

/** The lockup's metadata rows, spacers dropped; a bare number reads as its accessibility label ("135 views"). */
function lockupRows(metadata: Json): LockupPart[][] {
  const rows: Json[] = metadata?.metadata?.contentMetadataViewModel?.metadataRows ?? [];
  return rows
    .map((row) =>
      ((row.metadataParts ?? []) as Json[])
        .map((part): LockupPart | undefined => {
          const content = label(part.text);
          if (!content) return undefined;
          const text = /^[\d.,]+[KMB]?$/i.test(content) && part.accessibilityLabel ? part.accessibilityLabel : content;
          return { text, channelId: channelIdOf(part.text?.commandRuns) };
        })
        .filter((part): part is LockupPart => !!part),
    )
    .filter((row) => row.length > 0);
}

function lockupBadges(lockup: Json): { texts: string[]; live: boolean } {
  const texts: string[] = [];
  let live = false;
  walk(lockup.contentImage, (object) => {
    const badge = object.thumbnailBadgeViewModel;
    if (badge) {
      const text = label(badge.text);
      if (text) texts.push(text);
      if (/LIVE/.test(badge.badgeStyle ?? '') || text?.toUpperCase() === 'LIVE') live = true;
    }
    return false;
  });
  return { texts, live };
}

function lockupArtwork(lockup: Json): Artwork | undefined {
  const content = lockup.contentImage;
  const model = content?.thumbnailViewModel ?? content?.collectionThumbnailViewModel?.primaryThumbnail?.thumbnailViewModel;
  return image(model?.image);
}

function fromLockup(lockup: Json, owner: Owner): Entry | undefined {
  const id: string | undefined = lockup.contentId;
  const metadata = lockup.metadata?.lockupMetadataViewModel;
  const title = label(metadata?.title);
  if (!id || !title) return undefined;
  const rows = lockupRows(metadata);
  const badges = lockupBadges(lockup);
  const avatar = image(find(metadata?.image, 'avatarViewModel')?.image);

  switch (lockup.contentType) {
    case 'LOCKUP_CONTENT_TYPE_SHORTS':
      return undefined;
    case 'LOCKUP_CONTENT_TYPE_PLAYLIST':
    case 'LOCKUP_CONTENT_TYPE_PODCAST':
    case 'LOCKUP_CONTENT_TYPE_ALBUM': {
      const first = (rows[0] ?? []).filter((part) => !/^(playlist|podcast|album)$/i.test(part.text));
      const channelPart = first.find((part) => part.channelId);
      const count = badges.texts.find((text) => leadingCount(text) !== undefined) ?? badges.texts[0];
      const details = first.map((part) => part.text);
      if (count) details.push(count);
      return {
        kind: 'PLAYLIST',
        id,
        title,
        details,
        artwork: lockupArtwork(lockup),
        channel: channelPart ? { id: channelPart.channelId, name: channelPart.text } : owner.name ? owner : undefined,
      };
    }
    case 'LOCKUP_CONTENT_TYPE_CHANNEL':
      return { kind: 'CHANNEL', id, title, details: rows.flat().map((part) => part.text), artwork: lockupArtwork(lockup) };
    default: {
      // Outside a channel's own tabs, the first of several rows is the byline naming the channel.
      const bylinePart = rows.length > 1 && !rows[0].some((part) => mentionsViews(part.text)) ? rows[0][0] : undefined;
      const info = (bylinePart ? rows.slice(1) : rows).flat().map((part) => part.text);
      const duration = badges.texts.map(parseDuration).find((seconds) => seconds !== undefined);
      const live = badges.live || info.some(mentionsWatching);
      const upcoming =
        !live &&
        duration === undefined &&
        (info.some((text) => mentionsWaiting(text) || /scheduled|premieres/i.test(text)) ||
          badges.texts.some((text) => parseDuration(text) === undefined));
      return {
        kind: 'VIDEO',
        id,
        title,
        channel: bylinePart
          ? { id: bylinePart.channelId ?? channelIdOf(metadata?.image) ?? owner.id, name: bylinePart.text, avatar }
          : { ...owner, avatar: avatar ?? owner.avatar },
        durationSeconds: live ? undefined : duration,
        details: info.filter((text) => !(upcoming && mentionsWaiting(text))),
        live,
        upcoming,
        artwork: videoArtwork(id),
      };
    }
  }
}

function fromChannelRenderer(node: Json): ChannelEntry | undefined {
  const id: string | undefined = node.channelId;
  const title = label(node.title);
  if (!id || !title) return undefined;
  // The two count labels are not reliably in the field their name implies: a channelRenderer puts
  // the @handle under subscriberCountText and the subscribers under videoCountText.
  const labels = [label(node.subscriberCountText), label(node.videoCountText)].filter((text): text is string => !!text);
  const handle = labels.find((text) => text.startsWith('@'));
  const others = labels.filter((text) => text !== handle);
  return {
    kind: 'CHANNEL',
    id,
    title,
    details: handle ? [handle, ...others] : others,
    artwork: image(node.thumbnail),
  };
}

function fromPlaylistRenderer(node: Json): PlaylistEntry | undefined {
  const id: string | undefined = node.playlistId;
  const title = label(node.title);
  if (!id || !title) return undefined;
  const count =
    label(node.videoCountText) ?? label(node.videoCountShortText) ?? (node.videoCount ? `${node.videoCount} videos` : undefined);
  const owner = byline(node);
  return {
    kind: 'PLAYLIST',
    id,
    title,
    details: [owner?.name, count].filter((text): text is string => !!text),
    artwork: image(node.thumbnails?.[0]) ?? image(node.thumbnail),
    channel: owner,
  };
}
