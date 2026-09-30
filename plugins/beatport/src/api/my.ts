// The listener's own Beatport: who they follow, the new tracks from them, and their personal picks.
import { get } from './client';
import { LIST_PER_PAGE } from './paging';
import type { FollowedEntry, Paginated, Recommendation, Track } from './types';

/** The ids of every artist (performer) and label the listener follows. */
export async function followedIds(): Promise<{ performer: number[]; label: number[] }> {
  const followed = await get<{ results?: { performer?: number[]; label?: number[] } }>('my/beatport/');
  return { performer: followed.results?.performer ?? [], label: followed.results?.label ?? [] };
}

/** Followed artists and labels come as one plain list each, not paged. */
export const followedArtists = () => get<FollowedEntry[]>('my/beatport/artists/');
export const followedLabels = () => get<FollowedEntry[]>('my/beatport/labels/');

/** New tracks from what the listener follows ("My Beatport"), newest first, as the apps sort it. */
export function followedTracks(page: number, perPage = LIST_PER_PAGE): Promise<Paginated<Track>> {
  return get('my/beatport/tracks/', { page, per_page: perPage, order_by: '-release_date,release_id', preorder: false });
}

/** Personal picks, from the recommendations service outside /v4. */
export function recommendations(): Promise<Recommendation[]> {
  return get('/catalog/v1/recommendations/user/', {}, { expiresSession: false });
}
