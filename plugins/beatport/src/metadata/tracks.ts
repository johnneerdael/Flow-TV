// Every track of a playable list, a hundred at a time, for Play all: a release, a chart, a playlist,
// a Top 100, a genre's, artist's or label's Top 100 (their header's Play all), the listener's picks,
// or the My Beatport feed.
import type { TrackList, TracksRequest } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { catalogTracks, chartTracks, releaseTracks, topTracks } from '../api/catalog';
import { followedTracks } from '../api/my';
import { MAX_LIST_PAGES, TRACKS_PER_PAGE, nextCursor, pageOf } from '../api/paging';
import { playlistTracks } from '../api/playlists';
import type { Paginated, Track } from '../api/types';
import { isPresent } from '../util';
import { target } from '../pages/refs';
import { trackDescriptor } from '../pages/tracks';
import { forYouTracks } from './lists';

const FILTER_KEY = { genre: 'genre_id', artist: 'artist_id', label: 'label_id' } as const;

/** A Top 100 or the picks come whole; a cursor past them is not one this plugin issued. */
function onePage(page: number): void {
  if (page > 1) fail('NOT_FOUND', 'This list has no further pages');
}

async function pageOfTracks(request: TracksRequest, page: number): Promise<{ tracks: Track[]; response?: Paginated<unknown>; maxPage?: number }> {
  const found = target(request.entity);
  switch (found.type) {
    case 'release': {
      const response = await releaseTracks(found.id, page);
      return { tracks: response.results ?? [], response };
    }
    case 'chart': {
      const response = await chartTracks(found.id, page);
      return { tracks: response.results ?? [], response };
    }
    case 'playlist': {
      const response = await playlistTracks(found.source, found.id, page);
      return { tracks: (response.results ?? []).map((entry) => entry.track).filter(isPresent), response };
    }
    case 'top': {
      onePage(page);
      const scope = found.scope === 'all' ? ({ type: 'all' } as const) : { type: found.scope, id: found.id };
      return { tracks: (await topTracks(scope, 100, found.scope !== 'all' && found.hype === true)).results ?? [] };
    }
    case 'owner': {
      if (found.tab === 'tracks') {
        const response = await catalogTracks({ [FILTER_KEY[found.owner]]: found.id }, page, TRACKS_PER_PAGE);
        return { tracks: response.results ?? [], response, maxPage: MAX_LIST_PAGES };
      }
      if (found.tab !== undefined) break;
      onePage(page);
      return { tracks: (await topTracks({ type: found.owner, id: found.id }, 100)).results ?? [] };
    }
    case 'forYou':
      onePage(page);
      return { tracks: await forYouTracks() };
    case 'library': {
      if (found.section !== 'tracks') break;
      const response = await followedTracks(page, TRACKS_PER_PAGE);
      return { tracks: response.results ?? [], response };
    }
    default:
      break;
  }
  return fail('UNSUPPORTED', `${request.entity.kind} ${request.entity.providerId} has no tracks to play`);
}

export async function tracks(request: TracksRequest): Promise<TrackList> {
  const page = pageOf(request.cursor);
  const { tracks: found, response, maxPage } = await pageOfTracks(request, page);
  return {
    tracks: found.map(trackDescriptor),
    next: response ? nextCursor(response, page, maxPage) : undefined,
    source: request.entity,
  };
}
