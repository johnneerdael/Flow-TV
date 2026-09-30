// Search results: every kind in the order Beatport ranks them, each opening its own filter, or one
// kind's results a page at a time. Beatport's mixed search never answers charts, and its playlists
// are other listeners' private ones, so charts and Beatport's curated playlists come from their own
// searches.
import type { FilterControl, MetadataItem, MetadataPage, PageBlock } from '@milkbeat/plugin-sdk';
import type { SearchType } from '../api/catalog';
import type { SearchResults } from '../api/types';
import { present, shelf, table } from './blocks';
import { artistItem, chartItem, labelItem, playlistItem, releaseItem } from './items';
import { trackItems } from './tracks';

export const SEARCH_PAGE_ID = 'beatport/search';

const KINDS: { type: SearchType; label: string }[] = [
  { type: 'tracks', label: 'Tracks' },
  { type: 'releases', label: 'Releases' },
  { type: 'artists', label: 'Artists' },
  { type: 'labels', label: 'Labels' },
  { type: 'charts', label: 'Charts' },
  { type: 'playlists', label: 'Playlists' },
];

export const SEARCH_FILTERS: FilterControl = { options: KINDS.map((kind) => ({ id: kind.type, label: kind.label })) };

export function isSearchType(value: string): value is SearchType {
  return KINDS.some((kind) => kind.type === value);
}

const blockId = (type: SearchType) => `search/${type}`;

export function searchItems(results: SearchResults, type: SearchType): MetadataItem[] {
  const id = blockId(type);
  switch (type) {
    case 'tracks':
      return trackItems(results.tracks ?? [], id);
    case 'releases':
      return (results.releases ?? []).map((release) => releaseItem(release, id));
    case 'artists':
      return (results.artists ?? []).map((artist) => artistItem(artist, id));
    case 'labels':
      return (results.labels ?? []).map((label) => labelItem(label, id));
    case 'charts':
      return (results.charts ?? []).map((chart) => chartItem(chart, id));
    case 'playlists':
      return (results.playlists ?? []).map((playlist) => playlistItem(playlist, 'curated', id));
  }
}

function kindBlock(results: SearchResults, type: SearchType): PageBlock | undefined {
  const label = KINDS.find((kind) => kind.type === type)?.label ?? type;
  const items = searchItems(results, type);
  const options = { showAllFilterId: type };
  return type === 'tracks' ? table(blockId(type), label, items, options) : shelf(blockId(type), label, items, options);
}

/** The mixed results, with [charts] and [playlists] from their own searches. */
export function searchSummaryPage(mixed: SearchResults, charts: SearchResults | undefined, playlists: SearchResults | undefined): MetadataPage {
  const results: SearchResults = { ...mixed, charts: charts?.charts ?? [], playlists: playlists?.playlists ?? [] };
  const ranked = (mixed.order ?? []).filter(isSearchType);
  const order = [...ranked, ...KINDS.map((kind) => kind.type).filter((type) => !ranked.includes(type))];
  return { id: SEARCH_PAGE_ID, blocks: present(order.map((type) => kindBlock(results, type))), filters: SEARCH_FILTERS };
}

/** One kind's results, as a table that the next page extends. */
export function searchFilteredPage(results: SearchResults, type: SearchType, nextCursor: string | undefined): MetadataPage {
  const label = KINDS.find((kind) => kind.type === type)?.label;
  return {
    id: SEARCH_PAGE_ID,
    blocks: present([table(blockId(type), label, searchItems(results, type))]),
    filters: SEARCH_FILTERS,
    nextCursor,
  };
}
