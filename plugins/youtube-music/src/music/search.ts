// YouTube Music search as catalog pages. Unfiltered, it is the top result and then the results by
// kind, each with a Show all that re-runs the search with that kind's filter; filtered, one list that
// pages on. YouTube serves the summary either as titled shelves or as a flat ranked list; both read
// the same way. Filters are the app's (YouTube.SearchFilter), labelled with YouTube's own chips.
import type { FilterControl, MetadataItem, MetadataPage, PageBlock, PageBlockCollection } from '@milkbeat/plugin-sdk';
import { type Json, dig } from '../util';
import { firstRun, nextContinuation, runsText } from './renderers';
import { CARD_ARTWORK_SIZE, resultRow, topResult } from './results';
import { uniqueBlocks } from './shelves';

export const SEARCH_PAGE_ID = 'youtube-music/search';
export const RESULTS_BLOCK_ID = 'results';

type Kind = 'songs' | 'videos' | 'albums' | 'artists' | 'featured' | 'community';

interface SearchFilter {
  kind: Kind;
  id: string;
  /** What every params value selecting this kind starts with, whatever YouTube appends. */
  prefix: string;
  label: string;
}

export const SEARCH_FILTERS: SearchFilter[] = [
  { kind: 'songs', id: 'EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D', prefix: 'EgWKAQIIAW', label: 'Songs' },
  { kind: 'videos', id: 'EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D', prefix: 'EgWKAQIQAW', label: 'Videos' },
  { kind: 'albums', id: 'EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D', prefix: 'EgWKAQIYAW', label: 'Albums' },
  { kind: 'artists', id: 'EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D', prefix: 'EgWKAQIgAW', label: 'Artists' },
  { kind: 'featured', id: 'EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D', prefix: 'EgeKAQQoADgB', label: 'Featured playlists' },
  { kind: 'community', id: 'EgeKAQQoAEABagoQAxAEEAoQCRAF', prefix: 'EgeKAQQoAEAB', label: 'Community playlists' },
];

const filterFor = (params: string | undefined) => (params ? SEARCH_FILTERS.find((filter) => params.startsWith(filter.prefix)) : undefined);

function sectionList(response: Json): Json {
  return dig(response, 'contents', 'tabbedSearchResultsRenderer', 'tabs', 0, 'tabRenderer', 'content', 'sectionListRenderer');
}

/** The app's filters, in a fixed order so the chips stay put, labelled as YouTube labels its own. */
function filters(list: Json): { control: FilterControl; labels: Map<Kind, string> } {
  const labels = new Map<Kind, string>();
  for (const chip of dig(list, 'header', 'chipCloudRenderer', 'chips') ?? []) {
    const renderer = chip?.chipCloudChipRenderer;
    const filter = filterFor(renderer?.navigationEndpoint?.searchEndpoint?.params ?? renderer?.onDeselectedCommand?.searchEndpoint?.params);
    const label = firstRun(renderer?.text);
    if (filter && label && !labels.has(filter.kind)) labels.set(filter.kind, label);
  }
  return { control: { options: SEARCH_FILTERS.map((filter) => ({ id: filter.id, label: labels.get(filter.kind) ?? filter.label })) }, labels };
}

function kindOf(item: MetadataItem): Kind | undefined {
  switch (item.entity.kind) {
    case 'TRACK':
      return 'songs';
    case 'MUSIC_VIDEO':
      return 'videos';
    case 'ALBUM':
      return 'albums';
    case 'ARTIST':
      return 'artists';
    case 'PLAYLIST':
      return item.entity.providerId.startsWith('RDCLAK') ? 'featured' : 'community';
    default:
      return undefined;
  }
}

/** A table row takes the table's own view, whatever the item would be in a shelf. */
const asRow = (item: MetadataItem): MetadataItem => ({ ...item, view: undefined });

function collection(id: string, title: string, items: MetadataItem[], showAllFilterId?: string): PageBlockCollection {
  const playable = items.every((item) => item.track);
  return {
    type: 'collection',
    id,
    header: { title },
    layout: playable ? 'TRACK_TABLE' : 'HORIZONTAL_SHELF',
    defaultItemView: playable ? 'TRACK_ROW' : 'COVER_CARD',
    items: playable ? items.map(asRow) : items,
    showAllFilterId,
  };
}

function distinct(items: (MetadataItem | undefined)[]): MetadataItem[] {
  const seen = new Set<string>();
  return items.filter((item): item is MetadataItem => {
    if (!item || seen.has(item.id)) return false;
    seen.add(item.id);
    return true;
  });
}

/** The unfiltered search: the top result, then every kind of result with its Show all filter. */
export function searchSummaryPage(response: Json): MetadataPage {
  const list = sectionList(response);
  const { control, labels } = filters(list);
  const blocks: (PageBlock | undefined)[] = [];
  const flat = new Map<Kind, MetadataItem[]>();
  for (const content of list?.contents ?? []) {
    const card = content?.musicCardShelfRenderer;
    if (card) {
      const title = runsText(dig(card, 'header', 'musicCardShelfHeaderBasicRenderer', 'title')) ?? 'Top result';
      const rows: Json[] = (card.contents ?? []).map((row: Json) => row?.musicResponsiveListItemRenderer).filter(Boolean);
      const top = topResult(card, title);
      const owner = top?.entity.kind === 'ARTIST' ? [{ name: top.title, entity: top.entity }] : [];
      const items = distinct([top, ...rows.map((row) => resultRow(row, title, true, CARD_ARTWORK_SIZE, owner))]);
      if (items.length > 0) {
        blocks.push({ type: 'collection', id: title, header: { title }, layout: 'HORIZONTAL_SHELF', defaultItemView: 'COVER_CARD', items });
      }
      continue;
    }
    const shelf = content?.musicShelfRenderer;
    if (shelf) {
      const title = runsText(shelf.title) ?? 'Other results';
      const items = distinct((shelf.contents ?? []).map((row: Json) => resultRow(row?.musicResponsiveListItemRenderer, title, true)));
      const params: string | undefined = shelf.bottomEndpoint?.searchEndpoint?.params;
      if (items.length > 0) blocks.push(collection(title, title, items, filterFor(params)?.id ?? params));
      continue;
    }
    for (const row of content?.itemSectionRenderer?.contents ?? []) {
      const renderer = row?.musicResponsiveListItemRenderer;
      if (!renderer) continue;
      const probe = resultRow(renderer, '', true);
      const kind = probe && kindOf(probe);
      if (!kind) continue;
      if (!flat.has(kind)) flat.set(kind, []);
      flat.get(kind)?.push(probe);
    }
  }
  for (const [kind, found] of flat) {
    const filter = SEARCH_FILTERS.find((candidate) => candidate.kind === kind) as SearchFilter;
    const title = labels.get(kind) ?? filter.label;
    const items = distinct(found.map((item) => ({ ...item, id: `${title}${item.id}` })));
    blocks.push(collection(title, title, items, filter.id));
  }
  return { id: SEARCH_PAGE_ID, blocks: uniqueBlocks(blocks), filters: control };
}

/** One filter's results as a single list that pages on. */
export function searchFilteredPage(response: Json): MetadataPage {
  const list = sectionList(response);
  const contents: Json[] = list?.contents ?? [];
  const shelf = contents[contents.length - 1]?.musicShelfRenderer;
  return {
    id: SEARCH_PAGE_ID,
    blocks: uniqueBlocks([results(shelf?.contents ?? [], runsText(shelf?.title))]),
    filters: filters(list).control,
    nextCursor: nextContinuation(shelf?.continuations),
  };
}

/** More results of a filtered search, extending its list. */
export function searchContinuationPage(response: Json): MetadataPage {
  const shelf = dig(response, 'continuationContents', 'musicShelfContinuation');
  return {
    id: SEARCH_PAGE_ID,
    blocks: uniqueBlocks([results(shelf?.contents ?? [], undefined)]),
    nextCursor: nextContinuation(shelf?.continuations),
  };
}

function results(contents: Json[], title: string | undefined): PageBlockCollection | undefined {
  const items = distinct(contents.map((row) => resultRow(row?.musicResponsiveListItemRenderer, RESULTS_BLOCK_ID, false))).map(asRow);
  if (items.length === 0) return undefined;
  return {
    type: 'collection',
    id: RESULTS_BLOCK_ID,
    header: title ? { title } : null,
    layout: 'TRACK_TABLE',
    defaultItemView: 'TRACK_ROW',
    items,
  };
}
