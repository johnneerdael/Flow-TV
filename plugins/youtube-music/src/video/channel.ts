// A channel page: its header and the tabs the TV shows (Videos, Live, Playlists, About), ported from the
// app's channel/ parsers. The header comes from whichever of YouTube's three header generations the
// response uses; a tab's items come only from the selected tab's own grid, never the header's carousels.
import type { FilterOption, MetadataPage, PageBlock, PageBlockHeader } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import { type Entry, type Owner, toEntries } from './entries';
import { channelRef, collection, toItem } from './items';
import { find, image, label, listContinuation, walk } from './json';

export type ChannelTab = 'videos' | 'live' | 'playlists' | 'about';

interface TabSpec {
  label: string;
  /** Browse params, as the app's ChannelTabKind.defaultParams; the tab's URL names it in the response. */
  params?: string;
  segments: string[];
}

export const CHANNEL_TABS: Record<ChannelTab, TabSpec> = {
  videos: { label: 'Videos', params: 'EgZ2aWRlb3PyBgQKAjoA', segments: ['videos'] },
  live: { label: 'Live', params: 'EgdzdHJlYW1z8gYECgJ6AA==', segments: ['streams', 'live'] },
  playlists: { label: 'Playlists', params: 'EglwbGF5bGlzdHPyBgoKCEIGCgIQaCIA', segments: ['playlists'] },
  about: { label: 'About', segments: [] },
};

const TAB_ORDER: ChannelTab[] = ['videos', 'live', 'playlists', 'about'];

export const isChannelTab = (value: string | null | undefined): value is ChannelTab =>
  !!value && Object.prototype.hasOwnProperty.call(CHANNEL_TABS, value);

const tabRenderers = (response: Json): Json[] =>
  (response?.contents?.twoColumnBrowseResultsRenderer?.tabs ?? [])
    .map((tab: Json) => tab.tabRenderer ?? tab.expandableTabRenderer)
    .filter(Boolean);

/** The URL's last segment, lower-cased: `/@Cercle/streams` → `streams`; the channel root is `featured`. */
function tabSegment(tab: Json): string {
  const url: string = tab.endpoint?.commandMetadata?.webCommandMetadata?.url ?? '';
  const segment = url.split('?')[0].replace(/\/+$/, '').split('/').pop()?.toLowerCase() ?? '';
  return segment.startsWith('@') || segment.startsWith('uc') || segment === '' ? 'featured' : segment;
}

/** The tabs the TV offers, in its order, among those the channel has; About is always there. */
export function channelFilters(response: Json): FilterOption[] {
  const present = new Set(tabRenderers(response).map(tabSegment));
  return TAB_ORDER.filter((tab) => tab === 'about' || CHANNEL_TABS[tab].segments.some((segment) => present.has(segment))).map(
    (tab) => ({ id: tab, label: CHANNEL_TABS[tab].label }),
  );
}

function metadataRenderer(response: Json): Json {
  return response?.metadata?.channelMetadataRenderer ?? find(response, 'channelMetadataRenderer');
}

/** Only the first browse carries the header; a continuation is anonymous, so the channel id is passed along. */
export function channelOwner(response: Json, channelId: string): Owner {
  const metadata = metadataRenderer(response);
  return {
    id: metadata?.externalId ?? channelId,
    name: label(metadata?.title),
    avatar: image(metadata?.avatar),
  };
}

function headerRows(page: Json): string[] {
  const rows: Json[] = page?.metadata?.contentMetadataViewModel?.metadataRows ?? [];
  return rows.flatMap((row) => (row.metadataParts ?? []).map((part: Json) => label(part.text))).filter((text): text is string => !!text);
}

export function channelHeader(response: Json, channelId: string, hasVideos: boolean): PageBlockHeader {
  const page = find(response?.header, 'pageHeaderViewModel');
  const legacy = find(response?.header, 'c4TabbedHeaderRenderer');
  const metadata = metadataRenderer(response);
  const rows = headerRows(page);
  const handle = rows.find((text) => text.startsWith('@'));
  const subscribers = rows.find((text) => /subscriber/i.test(text)) ?? label(legacy?.subscriberCountText);
  const videos = rows.find((text) => /video/i.test(text) && !text.startsWith('@')) ?? label(legacy?.videosCountText);
  const id: string = metadata?.externalId ?? legacy?.channelId ?? channelId;
  return {
    type: 'header',
    id: 'header',
    style: 'PORTRAIT',
    entity: channelRef(id),
    title: label(page?.title?.dynamicTextViewModel?.text) ?? label(metadata?.title) ?? label(legacy?.title) ?? '',
    artwork: image(find(page?.image, 'avatarViewModel')?.image) ?? image(metadata?.avatar) ?? image(legacy?.avatar),
    details: [handle, subscribers, videos].filter((text): text is string => !!text),
    description: label(metadata?.description) ?? label(page?.description?.descriptionPreviewViewModel?.description),
    tracks: hasVideos ? channelRef(id) : undefined,
  };
}

const ITEM_LISTS: [string, string][] = [
  ['reloadContinuationItemsCommand', 'continuationItems'],
  ['appendContinuationItemsAction', 'continuationItems'],
  ['richGridContinuation', 'contents'],
  ['richGridRenderer', 'contents'],
  ['gridRenderer', 'items'],
  ['gridContinuation', 'items'],
  ['horizontalListRenderer', 'items'],
  ['expandedShelfContentsRenderer', 'items'],
];

/** Every list of grid items under [node], across the shapes a tab arrives in (browse, append, reload). */
function gridLists(node: Json): Json[][] {
  const lists: Json[][] = [];
  walk(node, (object) => {
    for (const [holder, field] of ITEM_LISTS) {
      const list = object[holder]?.[field];
      if (Array.isArray(list)) lists.push(list);
    }
    return false;
  });
  return lists;
}

/** The selected tab's content, so a walk cannot wander into the header or another tab. */
function selectedContent(response: Json): Json {
  const selected = tabRenderers(response).find((tab) => tab.selected === true);
  return selected?.content ?? response?.onResponseReceivedActions ?? response?.continuationContents ?? response;
}

export interface TabPage {
  entries: Entry[];
  next?: string;
}

/** One page of a grid tab: a first browse or any of its continuations. */
export function mapChannelTab(response: Json, owner: Owner): TabPage {
  const lists = gridLists(selectedContent(response));
  return {
    entries: toEntries(
      lists.flatMap((list) => list),
      owner,
    ),
    next: lists.map(listContinuation).find(Boolean),
  };
}

/** A tab's items; the block id is the same on every page so the host extends the grid. */
export const tabBlock = (tab: ChannelTab, entries: Entry[]): PageBlock =>
  collection(`tab:${tab}`, entries.map((entry) => toItem(entry)));

/** The About panel's token, which only the header's "…and N more links" suffix carries. */
export function aboutContinuation(response: Json): string | undefined {
  let token: string | undefined;
  walk(response, (object) => {
    const suffix = object.attributionViewModel?.suffix;
    if (!suffix) return false;
    const command = find(suffix, 'continuationCommand');
    token = command?.token;
    return !!token;
  });
  return token;
}

/** The header, completed with the About panel's totals, join date and country. */
export function withAbout(header: PageBlockHeader, about: Json): PageBlockHeader {
  const model = find(about, 'aboutChannelViewModel');
  if (!model) return header;
  const extra = [label(model.viewCountText), label(model.joinedDateText), label(model.country)].filter(
    (text): text is string => !!text,
  );
  return {
    ...header,
    description: label(model.description) ?? header.description,
    details: [...(header.details ?? []), ...extra],
  };
}

export function channelPage(id: string, blocks: PageBlock[], filters?: FilterOption[], nextCursor?: string): MetadataPage {
  return { id, blocks, filters: filters ? { options: filters } : undefined, nextCursor };
}
