// Beatport pages by number; a cursor is the next page's number. Its `next` link is not reused, as
// some endpoints answer it without a scheme.
import { fail } from '@milkbeat/plugin-sdk';
import type { Paginated } from './types';

/** Tracks come a hundred at a time, the most Beatport serves, so Play all needs few requests. */
export const TRACKS_PER_PAGE = 100;
export const LIST_PER_PAGE = 50;
/** Shelves show the first few of a list; their "show all" opens the rest. */
export const SHELF_SIZE = 20;
/**
 * The host reads a page's continuations to the end when it opens, so a list with thousands of
 * entries (a genre's charts, a big label's releases) stops after this many pages.
 */
export const MAX_LIST_PAGES = 8;

/** The page [cursor] asks for: 1 without one. */
export function pageOf(cursor: string | null | undefined): number {
  if (cursor === undefined || cursor === null || cursor === '') return 1;
  const page = Number(cursor);
  if (!Number.isInteger(page) || page < 2) fail('NOT_FOUND', 'The cursor is not one this plugin issued');
  return page;
}

/** The cursor after [page] of [response], unless it was the last one or [maxPage] was reached. */
export function nextCursor(response: Paginated<unknown>, page: number, maxPage = Number.MAX_SAFE_INTEGER): string | undefined {
  if (!response.next || page >= maxPage || (response.results?.length ?? 0) === 0) return undefined;
  return String(page + 1);
}
