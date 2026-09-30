// Beatport's home: the listener's picks, the Top 100s, the newest charts and the genres, then the
// listener's own playlists and followed artists. Each part is optional; a missing one is left out.
import type { MetadataPage } from '@milkbeat/plugin-sdk';
import { SHELF_SIZE } from '../api/paging';
import type { Chart, FollowedEntry, Genre, Playlist, Recommendation, Release, Track } from '../api/types';
import { columns, present, shelf } from './blocks';
import { artistItem, chartItem, genreItem, playlistItem, releaseItem } from './items';
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

export function homePage(parts: HomeParts): MetadataPage {
  const forYou = (parts.forYou ?? []).slice(0, SHELF_SIZE).map(recommendedTrack);
  return {
    id: HOME_PAGE_ID,
    blocks: present([
      columns('for-you', 'For You', trackItems(forYou, 'for-you'), { showAll: refs.forYou() }),
      columns('top-tracks', 'Beatport Top 100', trackItems((parts.topTracks ?? []).slice(0, SHELF_SIZE), 'top-tracks', 1), { showAll: refs.top('all') }),
      shelf('top-releases', 'Top 100 Releases', (parts.topReleases ?? []).map((release, index) => releaseItem(release, 'top-releases', index + 1)), {
        showAll: refs.topReleases(),
      }),
      shelf('new-charts', 'New Charts', (parts.charts ?? []).map((chart) => chartItem(chart, 'new-charts')), { showAll: refs.newCharts() }),
      shelf('genres', 'Genres', (parts.genres ?? []).map((genre) => genreItem(genre, 'genres'))),
      shelf('my-playlists', 'Your Playlists', (parts.playlists ?? []).map((playlist) => playlistItem(playlist, 'mine', 'my-playlists')), {
        showAll: refs.library('playlists'),
      }),
      shelf('followed-artists', 'Followed Artists', (parts.artists ?? []).map((artist) => artistItem(artist, 'followed-artists')), {
        showAll: refs.library('artists'),
      }),
    ]),
  };
}
