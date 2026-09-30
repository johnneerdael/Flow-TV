// The listener's library: an overview, or one section in full. The followed artists and labels come
// as one list; playlists and the My Beatport tracks page on.
import type { LibraryRequest, MetadataPage } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { followedArtists, followedLabels, followedTracks } from '../api/my';
import { SHELF_SIZE, TRACKS_PER_PAGE, nextCursor, pageOf } from '../api/paging';
import { myPlaylists } from '../api/playlists';
import { libraryOverviewPage, librarySectionPage } from '../pages/library';
import type { LibrarySection } from '../pages/refs';
import { settled } from './parts';

const SECTIONS: LibrarySection[] = ['playlists', 'tracks', 'artists', 'labels'];

export async function librarySection(section: LibrarySection, page: number): Promise<MetadataPage> {
  const first = page === 1;
  switch (section) {
    case 'playlists': {
      const list = await myPlaylists(page);
      return librarySectionPage({ section, playlists: list.results ?? [] }, first, nextCursor(list, page));
    }
    case 'tracks': {
      const list = await followedTracks(page, TRACKS_PER_PAGE);
      return librarySectionPage({ section, tracks: list.results ?? [] }, first, nextCursor(list, page));
    }
    case 'artists':
    case 'labels':
      if (!first) fail('NOT_FOUND', `Followed ${section} come on one page`);
      return section === 'artists'
        ? librarySectionPage({ section, artists: await followedArtists() }, true, undefined)
        : librarySectionPage({ section, labels: await followedLabels() }, true, undefined);
  }
}

export async function library(request: LibraryRequest): Promise<MetadataPage> {
  const { section, cursor } = request;
  if (section) {
    if (!SECTIONS.includes(section as LibrarySection)) fail('NOT_FOUND', `Beatport has no library section ${section}`);
    return librarySection(section as LibrarySection, pageOf(cursor));
  }
  if (cursor) fail('NOT_FOUND', 'The library overview has no further pages');
  const [playlists, tracks, artists, labels] = await settled([
    myPlaylists(1, SHELF_SIZE),
    followedTracks(1, SHELF_SIZE),
    followedArtists(),
    followedLabels(),
  ] as const);
  return libraryOverviewPage({ playlists: playlists?.results, tracks: tracks?.results, artists, labels });
}
