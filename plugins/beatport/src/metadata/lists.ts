// Pages of playable lists (releases, charts, playlists, Top 100s, the listener's picks) and of plain
// ones (top releases, new charts, genres). A first page carries the header; the rest extend its table.
import type { EntityRef, MetadataPage } from '@milkbeat/plugin-sdk';
import { artist, chart, chartTracks, charts, genre, genres, label, labelReleases, release, releaseTracks, topReleases, topTracks } from '../api/catalog';
import { recommendations } from '../api/my';
import { MAX_LIST_PAGES, SHELF_SIZE, TRACKS_PER_PAGE, nextCursor } from '../api/paging';
import { type PlaylistSource, playlist, playlistTracks } from '../api/playlists';
import type { Paginated, Release, Track } from '../api/types';
import { isPresent } from '../util';
import { chartHeader, listPage, playlistHeader, simpleHeader, trackListPage } from '../pages/collections';
import { releasesOf } from '../pages/genre';
import { chartItem, genreItem, releaseItem } from '../pages/items';
import type { Owner } from '../pages/refs';
import { releasePage, releaseTracksPage } from '../pages/release';
import { recommendedTrack } from '../pages/tracks';

const firstOrdinal = (page: number) => (page - 1) * TRACKS_PER_PAGE + 1;
const noReleases: Paginated<Release> = { results: [] };

export async function releaseEntity(id: string, page: number): Promise<MetadataPage> {
  if (page > 1) {
    const tracks = await releaseTracks(id, page);
    return releaseTracksPage(id, tracks.results ?? [], firstOrdinal(page), nextCursor(tracks, page));
  }
  const detail = release(id);
  const more = detail.then((found) => (found.label ? labelReleases(String(found.label.id), 1, SHELF_SIZE) : noReleases)).catch(() => noReleases);
  const [found, tracks, fromLabel] = await Promise.all([detail, releaseTracks(id, 1), more]);
  return releasePage(found, tracks.results ?? [], fromLabel.results ?? [], nextCursor(tracks, 1));
}

export async function chartEntity(entity: EntityRef, id: string, page: number): Promise<MetadataPage> {
  const [detail, tracks] = await Promise.all([page === 1 ? chart(id) : undefined, chartTracks(id, page)]);
  return trackListPage(entity, detail && chartHeader(detail), tracks.results ?? [], firstOrdinal(page), nextCursor(tracks, page));
}

export async function playlistEntity(entity: EntityRef, source: PlaylistSource, id: string, page: number): Promise<MetadataPage> {
  const [detail, entries] = await Promise.all([page === 1 ? playlist(source, id) : undefined, playlistTracks(source, id, page)]);
  const tracks = (entries.results ?? []).map((entry) => entry.track).filter(isPresent);
  return trackListPage(entity, detail && playlistHeader(source, detail), tracks, firstOrdinal(page), nextCursor(entries, page));
}

async function ownerName(scope: Owner, id: string): Promise<string | undefined> {
  const detail = scope === 'genre' ? await genre(id) : scope === 'artist' ? await artist(id) : await label(id);
  return detail.name;
}

/** A Top 100, which is one page: Beatport's, a genre's (or its Hype chart), an artist's or a label's. */
export async function topEntity(entity: EntityRef, scope: 'all' | Owner, id: string | undefined, hype: boolean): Promise<MetadataPage> {
  const title = hype ? 'Hype Top 100' : scope === 'all' || scope === 'genre' ? 'Beatport Top 100' : 'Top 100 Tracks';
  const [tracks, subtitle] = await Promise.all([
    topTracks(scope === 'all' || id === undefined ? { type: 'all' } : { type: scope, id }, 100, hype),
    scope === 'all' || id === undefined ? undefined : ownerName(scope, id),
  ]);
  return trackListPage(entity, simpleHeader(entity, title, subtitle, true), tracks.results ?? [], 1, undefined);
}

/** Beatport's Top 100 releases, or a genre's, read off its Top 100 tracks. */
export async function topReleasesEntity(entity: EntityRef, genreId: string | undefined): Promise<MetadataPage> {
  const [releases, subtitle] = await Promise.all([
    genreId === undefined ? topReleases(100).then((page) => page.results ?? []) : topTracks({ type: 'genre', id: genreId }, 100).then((page) => releasesOf(page.results ?? [])),
    genreId === undefined ? undefined : genre(genreId).then((found) => found.name),
  ]);
  const items = releases.map((found, index) => releaseItem(found, 'list', index + 1));
  return listPage(entity, undefined, items, undefined, simpleHeader(entity, 'Top 100 Releases', subtitle, false));
}

export async function newChartsEntity(entity: EntityRef, page: number): Promise<MetadataPage> {
  const list = await charts({ page });
  return listPage(entity, page === 1 ? 'New Charts' : undefined, (list.results ?? []).map((found) => chartItem(found, 'list')), nextCursor(list, page, MAX_LIST_PAGES));
}

export async function genresEntity(entity: EntityRef): Promise<MetadataPage> {
  return listPage(entity, 'Genres', (await genres()).map((found) => genreItem(found, 'list')));
}

export async function forYouTracks(): Promise<Track[]> {
  return (await recommendations()).map(recommendedTrack);
}

export async function forYouEntity(entity: EntityRef): Promise<MetadataPage> {
  return trackListPage(entity, simpleHeader(entity, 'For You', undefined, true), await forYouTracks(), 1, undefined);
}
