// Genre, artist and label pages: Featured, or one tab's list a page at a time. A list's first page
// reads its owner too, for the header or the owner's name; the pages after it read the list alone.
import type { MetadataPage } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { artist, artistReleases, catalogReleases, catalogTracks, charts, genre, label, labelReleases, topTracks } from '../api/catalog';
import { genreModules } from '../api/curation';
import { followedIds } from '../api/my';
import { LIST_PER_PAGE, MAX_LIST_PAGES, SHELF_SIZE, TRACKS_PER_PAGE, nextCursor } from '../api/paging';
import { curatedPlaylists } from '../api/playlists';
import type { Artist, Paginated } from '../api/types';
import { genreFeaturedPage } from '../pages/genre';
import { type ListTab, type OwnerDetail, type TabContent, artistFeaturedPage, labelFeaturedPage, tabPage } from '../pages/owners';
import { type Owner, type Tab, TABS } from '../pages/refs';
import { settled } from './parts';

const empty = <T>(page: Paginated<T> | undefined): T[] => page?.results ?? [];

function detailOf(owner: Owner, id: string): Promise<OwnerDetail> {
  return owner === 'genre' ? genre(id) : owner === 'artist' ? artist(id) : label(id);
}

async function genreFeatured(id: string): Promise<MetadataPage> {
  const [detail, modules, newCharts, releases, top, hypeTop, playlists] = await settled([
    genre(id),
    genreModules(id),
    charts({ genreId: id, perPage: SHELF_SIZE }),
    catalogReleases({ genre_id: id }, 1, SHELF_SIZE),
    topTracks({ type: 'genre', id }, 100),
    topTracks({ type: 'genre', id }, 10, true),
    curatedPlaylists(id, 1, SHELF_SIZE),
  ] as const);
  if (!detail) return fail('NOT_FOUND', `Beatport has no genre ${id}`);
  return genreFeaturedPage(detail, {
    modules: modules ?? [],
    charts: empty(newCharts),
    releases: empty(releases),
    top: empty(top),
    hypeTop: empty(hypeTop),
    playlists: empty(playlists),
  });
}

/** Whether the listener follows this artist or label; unknown (undefined) when Beatport will not say. */
function isFollowed(owner: Owner, id: string): Promise<boolean | undefined> {
  if (owner === 'genre') return Promise.resolve(undefined);
  return followedIds()
    .then((ids) => (owner === 'artist' ? ids.performer : ids.label).includes(Number(id)))
    .catch(() => undefined);
}

/** An artist's charts are those of the DJ profile tied to them, if there is one. */
const artistCharts = (detail: Artist, page: number, perPage: number) =>
  detail.dj_association ? charts({ djId: String(detail.dj_association), page, perPage }) : Promise.resolve({ results: [] });

async function artistFeatured(id: string): Promise<MetadataPage> {
  const detail = artist(id);
  const [found, releases, topTen, djCharts, followed] = await settled([
    detail,
    artistReleases(id, 1, SHELF_SIZE),
    topTracks({ type: 'artist', id }, 10),
    detail.then((found) => artistCharts(found, 1, SHELF_SIZE)),
    isFollowed('artist', id),
  ] as const);
  if (!found) return fail('NOT_FOUND', `Beatport has no artist ${id}`);
  return artistFeaturedPage(found, { followed, releases: empty(releases), charts: empty(djCharts), topTen: empty(topTen) });
}

async function labelFeatured(id: string): Promise<MetadataPage> {
  const [found, releases, topTen, followed] = await settled([
    label(id),
    labelReleases(id, 1, SHELF_SIZE),
    topTracks({ type: 'label', id }, 10),
    isFollowed('label', id),
  ] as const);
  if (!found) return fail('NOT_FOUND', `Beatport has no label ${id}`);
  return labelFeaturedPage(found, { followed, releases: empty(releases), topTen: empty(topTen) });
}

const FILTER_KEY: Record<Owner, string> = { genre: 'genre_id', artist: 'artist_id', label: 'label_id' };

/** One page of [tab]; the list and whether another page follows it. */
async function tabContent(
  owner: Owner,
  id: string,
  tab: ListTab,
  page: number,
  detail: Promise<OwnerDetail | undefined>,
): Promise<{ content: TabContent; next?: string }> {
  const filter = { [FILTER_KEY[owner]]: id };
  switch (tab) {
    case 'tracks': {
      const tracks = await catalogTracks(filter, page, TRACKS_PER_PAGE);
      return { content: { tab, tracks: empty(tracks) }, next: nextCursor(tracks, page, MAX_LIST_PAGES) };
    }
    case 'releases': {
      const releases =
        owner === 'artist' ? await artistReleases(id, page) : owner === 'label' ? await labelReleases(id, page) : await catalogReleases(filter, page);
      return { content: { tab, releases: empty(releases) }, next: nextCursor(releases, page, MAX_LIST_PAGES) };
    }
    case 'charts': {
      const list = owner === 'artist' ? await artistCharts((await detail) as Artist, page, LIST_PER_PAGE) : await charts({ genreId: id, page });
      return { content: { tab, charts: empty(list) }, next: nextCursor(list, page, MAX_LIST_PAGES) };
    }
    case 'playlists': {
      const playlists = await curatedPlaylists(id, page);
      return { content: { tab, playlists: empty(playlists) }, next: nextCursor(playlists, page, MAX_LIST_PAGES) };
    }
  }
}

/**
 * The page of a genre, artist or label: [tab] selects one (Featured without it), and [standalone]
 * says the tab was opened as a page of its own from a "show all".
 */
export async function ownerPage(owner: Owner, id: string, tab: Tab | undefined, page: number, standalone: boolean): Promise<MetadataPage> {
  const selected = tab ?? 'featured';
  if (!TABS[owner].includes(selected)) fail('NOT_FOUND', `A Beatport ${owner} has no ${selected} tab`);
  if (selected === 'featured') {
    if (page > 1) fail('NOT_FOUND', 'Featured has no further pages');
    return owner === 'genre' ? genreFeatured(id) : owner === 'artist' ? artistFeatured(id) : labelFeatured(id);
  }
  const needsDetail = page === 1 || (owner === 'artist' && selected === 'charts');
  const detail: Promise<OwnerDetail | undefined> = needsDetail ? detailOf(owner, id) : Promise.resolve(undefined);
  const followed = page === 1 && !standalone ? isFollowed(owner, id) : Promise.resolve(undefined);
  const [{ content, next }, found, follows] = await Promise.all([tabContent(owner, id, selected, page, detail), detail, followed]);
  return tabPage(owner, id, content, next, { standalone, detail: page === 1 ? found : undefined, followed: follows });
}
