// Beatport's public catalog: the Top 100s, genres, charts, releases, artists, labels and search.
import { type Params, get } from './client';
import { LIST_PER_PAGE, TRACKS_PER_PAGE } from './paging';
import type { Artist, Chart, Genre, Label, Paginated, Release, SearchResults, Track } from './types';

export type TopScope = { type: 'all' } | { type: 'genre' | 'artist' | 'label'; id: string };

/** The Top 100 of all Beatport, a genre, an artist or a label, or its first [size]; [hype] for the Hype chart. */
export function topTracks(scope: TopScope, size = 100, hype = false): Promise<Paginated<Track>> {
  const path = scope.type === 'all' ? 'catalog/tracks/top/100/' : `catalog/${scope.type}s/${scope.id}/top/${size}/`;
  return get(path, { per_page: size, hype: hype || undefined });
}

export function topReleases(perPage = TRACKS_PER_PAGE): Promise<Paginated<Release>> {
  return get('catalog/releases/top/100/', { per_page: perPage });
}

const GENRES_FRESH_MS = 6 * 60 * 60 * 1000;
let genresCache: { at: number; genres: Promise<Genre[]> } | undefined;

/** Beatport's genres, read once per few hours: home, genre pages and search all need them. */
export function genres(): Promise<Genre[]> {
  if (genresCache && Date.now() - genresCache.at < GENRES_FRESH_MS) return genresCache.genres;
  const genres = get<Paginated<Genre>>('catalog/genres/', { per_page: 100 }).then((page) => page.results ?? []);
  genresCache = { at: Date.now(), genres };
  genres.catch(() => {
    if (genresCache?.genres === genres) genresCache = undefined;
  });
  return genres;
}

export function resetCatalogCaches(): void {
  genresCache = undefined;
}

export async function genre(id: string): Promise<Genre> {
  const cached = (await genres()).find((genre) => String(genre.id) === id);
  return cached ?? get<Genre>(`catalog/genres/${id}/`);
}

/** The newest charts, of one genre, of one DJ, or of everyone. */
export function charts(options: { genreId?: string; djId?: string; page?: number; perPage?: number } = {}): Promise<Paginated<Chart>> {
  return get('catalog/charts/', {
    genre_id: options.genreId,
    dj_id: options.djId,
    order_by: '-publish_date',
    page: options.page ?? 1,
    per_page: options.perPage ?? LIST_PER_PAGE,
  });
}

/** Today, as Beatport's date filters take it: `publish_date=:<today>` leaves out pre-orders. */
const today = () => new Date(Date.now()).toISOString().slice(0, 10);

/** Released tracks of a genre, artist or label, newest first; pre-orders cannot be streamed yet. */
export function catalogTracks(filter: Params, page: number, perPage = TRACKS_PER_PAGE): Promise<Paginated<Track>> {
  return get('catalog/tracks/', { ...filter, order_by: '-publish_date', preorder: false, page, per_page: perPage });
}

/** Released releases of a genre, newest first. */
export function catalogReleases(filter: Params, page: number, perPage = LIST_PER_PAGE): Promise<Paginated<Release>> {
  return get('catalog/releases/', { ...filter, order_by: '-publish_date', publish_date: `:${today()}`, page, per_page: perPage });
}

export const chart = (id: string) => get<Chart>(`catalog/charts/${id}/`);
export const chartTracks = (id: string, page: number) => get<Paginated<Track>>(`catalog/charts/${id}/tracks/`, { page, per_page: TRACKS_PER_PAGE });

export const release = (id: string) => get<Release>(`catalog/releases/${id}/`);
export const releaseTracks = (id: string, page: number) => get<Paginated<Track>>(`catalog/releases/${id}/tracks/`, { page, per_page: TRACKS_PER_PAGE });

export const artist = (id: string) => get<Artist>(`catalog/artists/${id}/`);

/** An artist's releases, newest first, as the app lists them. */
export function artistReleases(id: string, page: number, perPage = LIST_PER_PAGE): Promise<Paginated<Release>> {
  return get('catalog/releases/', { artist_id: id, order_by: '-release_date', page, per_page: perPage });
}

export const label = (id: string) => get<Label>(`catalog/labels/${id}/`);

/** A label's releases, newest first. */
export function labelReleases(id: string, page: number, perPage = LIST_PER_PAGE): Promise<Paginated<Release>> {
  return get(`catalog/labels/${id}/releases/`, { page, per_page: perPage });
}

export type SearchType = 'tracks' | 'artists' | 'releases' | 'labels' | 'charts' | 'playlists';

/** A search of every kind at once, or of one [type] a page at a time; [filters] need a type. */
export function search(
  q: string,
  options: { type?: SearchType; page?: number; perPage?: number; filters?: Record<string, string> } = {},
): Promise<SearchResults> {
  return get('catalog/search/', { q, type: options.type, page: options.page, per_page: options.perPage, ...options.filters });
}
