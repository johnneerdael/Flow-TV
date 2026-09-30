// Search: the mixed results with charts and Beatport's curated playlists alongside, or one kind a
// page at a time. Curated playlists are the ones filed under a genre, so their search names them all.
import type { MetadataPage, SearchRequest } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { type SearchType, genres, search as searchCatalog } from '../api/catalog';
import { LIST_PER_PAGE, nextCursor, pageOf } from '../api/paging';
import type { SearchResults } from '../api/types';
import { isSearchType, searchFilteredPage, searchSummaryPage } from '../pages/search';
import { settled } from './parts';

const SUMMARY_SIZE = 10;

async function curatedOnly(): Promise<Record<string, string>> {
  return { genre_id: (await genres()).map((genre) => genre.id).join(',') };
}

async function searchOf(query: string, type: SearchType, page: number, perPage: number): Promise<SearchResults> {
  const filters = type === 'playlists' ? await curatedOnly() : undefined;
  return searchCatalog(query, { type, page, perPage, filters });
}

export async function search(request: SearchRequest): Promise<MetadataPage> {
  const query = request.query.trim();
  if (!query) fail('NOT_FOUND', 'Nothing to search for');
  if (request.filterId) {
    if (!isSearchType(request.filterId)) fail('NOT_FOUND', `No search filter ${request.filterId}`);
    const type = request.filterId;
    const page = pageOf(request.cursor);
    const results = await searchOf(query, type, page, LIST_PER_PAGE);
    return searchFilteredPage(results, type, nextCursor({ next: results.next, results: results[type] }, page));
  }
  if (request.cursor) fail('NOT_FOUND', 'The mixed search has no further pages; open a filter');
  const [mixed, charts, playlists] = await settled([
    searchCatalog(query, { perPage: SUMMARY_SIZE }),
    searchOf(query, 'charts', 1, SUMMARY_SIZE),
    searchOf(query, 'playlists', 1, SUMMARY_SIZE),
  ] as const);
  return searchSummaryPage(mixed ?? {}, charts, playlists);
}
