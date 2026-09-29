// YouTube search, as the app's SearchPageParser read it: results in YouTube's order, with the shelves it
// interleaves ("Latest from …", "People also watched") as their own blocks. Filters are the TV's.
import type { FilterControl, FilterOption, MetadataPage, PageBlock } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import { toEntries, toEntry } from './entries';
import { collection, toItem } from './items';
import { continuationToken, label } from './json';

interface SearchFilter extends FilterOption {
  /** The `params` token YouTube's own filter dialog sends (see the app's YouTubeSearchParams). */
  params: string;
}

export const SEARCH_FILTERS: SearchFilter[] = [
  { id: 'videos', label: 'Videos', params: 'EgIQAQ%3D%3D' },
  { id: 'channels', label: 'Channels', params: 'EgIQAg%3D%3D' },
  { id: 'playlists', label: 'Playlists', params: 'EgIQAw%3D%3D' },
  { id: 'live', label: 'Live', params: 'EgQQAUAB' },
];

export const searchFilters = (): FilterControl => ({ options: SEARCH_FILTERS.map(({ id, label }) => ({ id, label })) });

export const RESULTS_BLOCK = 'results';

/** The first page nests the feed under the two-column renderer; a continuation returns it flat. */
function feedEntries(response: Json): Json[] {
  const sections: Json[] | undefined =
    response?.contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents;
  const lists: Json[] = sections
    ? [sections]
    : (response?.onResponseReceivedCommands ?? []).map((command: Json) => command.appendContinuationItemsAction?.continuationItems);
  const entries: Json[] = [];
  for (const list of lists) {
    for (const entry of Array.isArray(list) ? list : []) {
      const nested = entry?.itemSectionRenderer?.contents;
      if (Array.isArray(nested)) entries.push(...nested);
      else entries.push(entry);
    }
  }
  return entries;
}

/** A shelf's title and entries, from whichever of its containers it arrived in. */
function shelf(entry: Json): { title?: string; entries: Json[] } | undefined {
  const renderer = entry.shelfRenderer ?? entry.richShelfRenderer;
  if (renderer) {
    const content = renderer.content ?? {};
    const entries =
      renderer.items ??
      renderer.contents ??
      content.verticalListRenderer?.items ??
      content.horizontalListRenderer?.items ??
      content.expandedShelfContentsRenderer?.items ??
      content.gridRenderer?.items ??
      [];
    return { title: label(renderer.title), entries };
  }
  if (entry.reelShelfRenderer || entry.gridShelfViewModel) return { entries: [] };
  return undefined;
}

export function mapSearch(response: Json, pageId: string, withFilters: boolean): MetadataPage {
  const results = [];
  const shelves: PageBlock[] = [];
  const seen = new Set<string>();
  let nextCursor: string | undefined;

  for (const entry of feedEntries(response)) {
    if (entry.continuationItemRenderer) {
      nextCursor = continuationToken(entry.continuationItemRenderer) ?? nextCursor;
      continue;
    }
    const strip = shelf(entry);
    if (strip) {
      const entries = toEntries(strip.entries);
      if (entries.length === 0) continue;
      const id = `shelf:${entries[0].id}`;
      shelves.push(collection(id, entries.map((item) => toItem(item, `${id}/`)), strip.title ? { title: strip.title } : null));
      continue;
    }
    const found = toEntry(entry);
    if (found && !seen.has(`${found.kind}:${found.id}`)) {
      seen.add(`${found.kind}:${found.id}`);
      results.push(toItem(found));
    }
  }

  return {
    id: pageId,
    blocks: [collection(RESULTS_BLOCK, results), ...shelves],
    filters: withFilters ? searchFilters() : undefined,
    nextCursor,
  };
}
