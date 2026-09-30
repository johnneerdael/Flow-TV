// Playlists live in two places: Beatport's curated ones under curation/ (a genre's are the ones with
// that genre) and the listener's own under my/.
import { get } from './client';
import { LIST_PER_PAGE, TRACKS_PER_PAGE } from './paging';
import type { Paginated, Playlist, PlaylistEntry } from './types';

export type PlaylistSource = 'curated' | 'mine';

const BASE: Record<PlaylistSource, string> = {
  curated: 'curation/playlists/',
  mine: 'my/playlists/',
};

/** Beatport's curated playlists for a genre, newest first; without a genre the list is every listener's. */
export function curatedPlaylists(genreId: string, page: number, perPage = LIST_PER_PAGE): Promise<Paginated<Playlist>> {
  return get(BASE.curated, { genre_id: genreId, page, per_page: perPage });
}

export function myPlaylists(page: number, perPage = LIST_PER_PAGE): Promise<Paginated<Playlist>> {
  return get(BASE.mine, { page, per_page: perPage });
}

export const playlist = (source: PlaylistSource, id: string) => get<Playlist>(`${BASE[source]}${id}/`);

export function playlistTracks(source: PlaylistSource, id: string, page: number): Promise<Paginated<PlaylistEntry>> {
  return get(`${BASE[source]}${id}/tracks/`, { page, per_page: TRACKS_PER_PAGE });
}
