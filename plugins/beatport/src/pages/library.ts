// The listener's library: their playlists, the new tracks from who they follow ("My Beatport"), and
// the artists and labels they follow. The overview shows a little of each; a section shows it all.
import type { MetadataItem, MetadataPage } from '@milkbeat/plugin-sdk';
import { SHELF_SIZE } from '../api/paging';
import type { FollowedEntry, Playlist, Track } from '../api/types';
import { columns, present, shelf, table } from './blocks';
import { artistItem, labelItem, playlistItem } from './items';
import { type LibrarySection, refs } from './refs';
import { trackItems } from './tracks';

export const SECTION_TITLES: Record<LibrarySection, string> = {
  playlists: 'Your Playlists',
  tracks: 'My Beatport',
  artists: 'Followed Artists',
  labels: 'Followed Labels',
};

const pageId = (section?: LibrarySection) => `beatport/library${section ? `/${section}` : ''}`;

export interface LibraryParts {
  playlists?: Playlist[];
  tracks?: Track[];
  artists?: FollowedEntry[];
  labels?: FollowedEntry[];
}

export function libraryOverviewPage(parts: LibraryParts): MetadataPage {
  return {
    id: pageId(),
    blocks: present([
      shelf('playlists', SECTION_TITLES.playlists, (parts.playlists ?? []).map((playlist) => playlistItem(playlist, 'mine', 'playlists')), {
        showAll: refs.library('playlists'),
      }),
      columns('tracks', SECTION_TITLES.tracks, trackItems((parts.tracks ?? []).slice(0, SHELF_SIZE), 'tracks'), { showAll: refs.library('tracks') }),
      shelf('artists', SECTION_TITLES.artists, (parts.artists ?? []).map((artist) => artistItem(artist, 'artists')), { showAll: refs.library('artists') }),
      shelf('labels', SECTION_TITLES.labels, (parts.labels ?? []).map((label) => labelItem(label, 'labels')), { showAll: refs.library('labels') }),
    ]),
  };
}

export type SectionContent =
  | { section: 'playlists'; playlists: Playlist[] }
  | { section: 'tracks'; tracks: Track[] }
  | { section: 'artists'; artists: FollowedEntry[] }
  | { section: 'labels'; labels: FollowedEntry[] };

function sectionItems(content: SectionContent): MetadataItem[] {
  const id = content.section;
  switch (content.section) {
    case 'playlists':
      return content.playlists.map((playlist) => playlistItem(playlist, 'mine', id));
    case 'tracks':
      return trackItems(content.tracks, id);
    case 'artists':
      return content.artists.map((artist) => artistItem(artist, id));
    case 'labels':
      return content.labels.map((label) => labelItem(label, id));
  }
}

/** One section in full, titled on its first page, extended by the next. */
export function librarySectionPage(content: SectionContent, first: boolean, nextCursor: string | undefined): MetadataPage {
  return {
    id: pageId(content.section),
    blocks: present([table(content.section, first ? SECTION_TITLES[content.section] : undefined, sectionItems(content))]),
    nextCursor,
  };
}
