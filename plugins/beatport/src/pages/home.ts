// Beatport's home: the genres as chips across the top, then the listener's picks, the Top 100s, the
// newest charts, and the listener's own playlists and followed artists. Each part is optional; a
// missing one is left out. A chip shows its genre's featured shelves in place of the rest.
import type { FilterControl, MetadataPage, PageBlock } from '@milkbeat/plugin-sdk';
import { SHELF_SIZE } from '../api/paging';
import type { Chart, FollowedEntry, Genre, Playlist, Recommendation, Release, Track } from '../api/types';
import { columns, present, shelf } from './blocks';
import { artistItem, chartItem, playlistItem, releaseItem } from './items';
import { refs } from './refs';
import { recommendedTrack, trackItems } from './tracks';

export const HOME_PAGE_ID = 'beatport/home';

export interface HomeParts {
  forYou?: Recommendation[];
  topTracks?: Track[];
  topReleases?: Release[];
  charts?: Chart[];
  genres?: Genre[];
  playlists?: Playlist[];
  artists?: FollowedEntry[];
}

const GENRE_CHIP = 'genre:';

/** The home chip of each genre, in Beatport's order. */
export function genreChips(genres: Genre[] | undefined): FilterControl | undefined {
  const options = (genres ?? []).filter((genre) => genre.name).map((genre) => ({ id: `${GENRE_CHIP}${genre.id}`, label: genre.name as string }));
  return options.length > 0 ? { options } : undefined;
}

/** The genre a home chip stands for, or undefined when [filterId] is no genre chip. */
export function chipGenre(filterId: string): string | undefined {
  const id = filterId.startsWith(GENRE_CHIP) ? filterId.slice(GENRE_CHIP.length) : '';
  return /^\d+$/.test(id) ? id : undefined;
}

/** Home with a genre chip on: the genre's featured shelves, without the genre page's own header. */
export function genreHomePage(genres: Genre[] | undefined, featured: MetadataPage): MetadataPage {
  return {
    id: HOME_PAGE_ID,
    blocks: featured.blocks.filter((block: PageBlock) => block.type !== 'header'),
    filters: genreChips(genres),
  };
}

export function homePage(parts: HomeParts): MetadataPage {
  const forYou = (parts.forYou ?? []).slice(0, SHELF_SIZE).map(recommendedTrack);
  return {
    id: HOME_PAGE_ID,
    filters: genreChips(parts.genres),
    blocks: present([
      columns('for-you', 'For You', trackItems(forYou, 'for-you'), { showAll: refs.forYou() }),
      columns('top-tracks', 'Beatport Top 100', trackItems((parts.topTracks ?? []).slice(0, SHELF_SIZE), 'top-tracks', 1), { showAll: refs.top('all') }),
      shelf('top-releases', 'Top 100 Releases', (parts.topReleases ?? []).map((release, index) => releaseItem(release, 'top-releases', index + 1)), {
        showAll: refs.topReleases(),
      }),
      shelf('new-charts', 'New Charts', (parts.charts ?? []).map((chart) => chartItem(chart, 'new-charts')), { showAll: refs.newCharts() }),
      shelf('my-playlists', 'Your Playlists', (parts.playlists ?? []).map((playlist) => playlistItem(playlist, 'mine', 'my-playlists')), {
        showAll: refs.library('playlists'),
      }),
      shelf('followed-artists', 'Followed Artists', (parts.artists ?? []).map((artist) => artistItem(artist, 'followed-artists')), {
        showAll: refs.library('artists'),
      }),
    ]),
  };
}
