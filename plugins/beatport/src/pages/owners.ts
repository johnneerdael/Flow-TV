// Genres, artists and labels: a header, then tabs as the web store has them (Featured, Tracks,
// Releases, Charts, and a genre's curated playlists). The host does not pass a tab to entity pages
// yet, so every Featured shelf's "show all" opens its tab as a page of its own.
import type { FilterControl, MetadataItem, MetadataPage, PageBlock, PageBlockHeader } from '@milkbeat/plugin-sdk';
import type { Artist, Chart, Genre, Label, Playlist, Release, Track } from '../api/types';
import { isPresent } from '../util';
import { LARGE, artwork } from './artwork';
import { present, shelf, table } from './blocks';
import { chartItem, playlistItem, releaseItem } from './items';
import { type Owner, type Tab, TABS, refs } from './refs';
import { trackItems } from './tracks';

export type OwnerDetail = Genre | Artist | Label;
export type ListTab = Exclude<Tab, 'featured'>;

const TAB_LABELS: Record<Tab, string> = {
  featured: 'Featured',
  tracks: 'Tracks',
  releases: 'Releases',
  charts: 'Charts',
  playlists: 'Playlists',
};

export const ownerFilters = (owner: Owner): FilterControl => ({ options: TABS[owner].map((tab) => ({ id: tab, label: TAB_LABELS[tab] })) });

export const pageId = (owner: Owner, id: string, tab?: Tab) => `beatport/${owner}/${id}${tab && tab !== 'featured' ? `/${tab}` : ''}`;

/** [followed]: whether the listener follows this artist or label on Beatport, when known. */
export function ownerHeader(owner: Owner, detail: OwnerDetail, followed?: boolean): PageBlockHeader {
  const common = { type: 'header' as const, id: 'header', entity: refs.owner(owner, detail.id), title: detail.name ?? '' };
  if (owner === 'genre') {
    const genre = detail as Genre;
    const subGenres = (genre.sub_genres ?? []).map((sub) => sub.name).filter(isPresent);
    return {
      ...common,
      style: 'PORTRAIT',
      details: [genre.category?.name, subGenres.length > 0 ? subGenres.join(', ') : undefined].filter(isPresent),
      tracks: refs.top('genre', detail.id),
    };
  }
  const person = detail as Artist | Label;
  return {
    ...common,
    style: 'PORTRAIT',
    artwork: artwork(person.image, LARGE),
    details: followed ? ['Following on Beatport'] : [],
    description: person.bio?.trim() || undefined,
    tracks: refs.top(owner, detail.id),
  };
}

export type TabContent = { tab: 'tracks'; tracks: Track[] } | { tab: 'releases'; releases: Release[] } | { tab: 'charts'; charts: Chart[] } | { tab: 'playlists'; playlists: Playlist[] };

const tabBlockId = (tab: ListTab) => `tab/${tab}`;

export function tabItems(content: TabContent): MetadataItem[] {
  const id = tabBlockId(content.tab);
  switch (content.tab) {
    case 'tracks':
      return trackItems(content.tracks, id);
    case 'releases':
      return content.releases.map((release) => releaseItem(release, id));
    case 'charts':
      return content.charts.map((chart) => chartItem(chart, id));
    case 'playlists':
      return content.playlists.map((playlist) => playlistItem(playlist, 'curated', id));
  }
}

/**
 * One tab's list, as a tab of the entity page or, [standalone], as a page of its own. [detail] comes
 * with the first page: the entity page shows its header and tabs, a standalone page names the owner.
 */
export function tabPage(
  owner: Owner,
  id: string,
  content: TabContent,
  nextCursor: string | undefined,
  options: { standalone: boolean; detail?: OwnerDetail; followed?: boolean },
): MetadataPage {
  const { standalone, detail, followed } = options;
  const title = content.tab === 'playlists' ? 'Beatport Playlists' : TAB_LABELS[content.tab];
  const context = detail && standalone ? { context: detail.name, target: refs.owner(owner, id) } : {};
  const list = table(tabBlockId(content.tab), detail ? title : undefined, tabItems(content), context);
  const header = detail && !standalone ? ownerHeader(owner, detail, followed) : undefined;
  return {
    id: standalone ? `${pageId(owner, id, content.tab)}/list` : pageId(owner, id, content.tab),
    blocks: present([header, list]),
    filters: detail && !standalone ? ownerFilters(owner) : undefined,
    nextCursor,
  };
}

export function featuredPage(owner: Owner, detail: OwnerDetail, blocks: (PageBlock | undefined)[], followed?: boolean): MetadataPage {
  return { id: pageId(owner, String(detail.id)), blocks: present([ownerHeader(owner, detail, followed), ...blocks]), filters: ownerFilters(owner) };
}

export interface ArtistFeatured {
  followed?: boolean;
  releases: Release[];
  charts: Chart[];
  topTen: Track[];
}

export function artistFeaturedPage(artist: Artist, parts: ArtistFeatured): MetadataPage {
  return featuredPage('artist', artist, [
    shelf('latest-releases', 'Latest Releases', parts.releases.map((release) => releaseItem(release, 'latest-releases')), {
      showAll: refs.list('artist', artist.id, 'releases'),
    }),
    shelf('charts', 'Charts', parts.charts.map((chart) => chartItem(chart, 'charts')), { showAll: refs.list('artist', artist.id, 'charts') }),
    table('top-10', 'Top Ten Tracks', trackItems(parts.topTen.slice(0, 10), 'top-10', 1), { showAll: refs.top('artist', artist.id) }),
  ], parts.followed);
}

export interface LabelFeatured {
  followed?: boolean;
  releases: Release[];
  topTen: Track[];
}

export function labelFeaturedPage(label: Label, parts: LabelFeatured): MetadataPage {
  return featuredPage('label', label, [
    shelf('latest-releases', 'Latest Releases', parts.releases.map((release) => releaseItem(release, 'latest-releases')), {
      showAll: refs.list('label', label.id, 'releases'),
    }),
    table('top-10', 'Top 10 Tracks', trackItems(parts.topTen.slice(0, 10), 'top-10', 1), { showAll: refs.top('label', label.id) }),
  ], parts.followed);
}
