// The home page: one bounded round of requests, one per shelf, all at once. A genre chip loads that
// genre's featured shelves instead.
import type { HomeRequest, MetadataPage } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { charts, genres, topReleases, topTracks } from '../api/catalog';
import { followedArtists, recommendations } from '../api/my';
import { SHELF_SIZE } from '../api/paging';
import { myPlaylists } from '../api/playlists';
import { chipGenre, genreHomePage, homePage } from '../pages/home';
import { genreFeatured } from './owners';
import { settled } from './parts';

export async function home(request: HomeRequest): Promise<MetadataPage> {
  if (request.cursor) fail('NOT_FOUND', 'Beatport’s home has no further pages');
  if (request.filterId) {
    const genreId = chipGenre(request.filterId) ?? fail('NOT_FOUND', `Beatport’s home has no chip ${request.filterId}`);
    const [allGenres, featured] = await Promise.all([genres(), genreFeatured(genreId)]);
    return genreHomePage(allGenres, featured);
  }
  const [forYou, top, releases, newCharts, allGenres, playlists, artists] = await settled([
    recommendations(),
    topTracks({ type: 'all' }, SHELF_SIZE),
    topReleases(SHELF_SIZE),
    charts({ perPage: SHELF_SIZE }),
    genres(),
    myPlaylists(1, SHELF_SIZE),
    followedArtists(),
  ] as const);
  return homePage({
    forYou,
    topTracks: top?.results,
    topReleases: releases?.results,
    charts: newCharts?.results,
    genres: allGenres,
    playlists: playlists?.results,
    artists,
  });
}
