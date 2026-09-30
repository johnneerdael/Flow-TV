// A genre's Featured tab, laid out as the web store's genre page: Beatport's editorial modules when
// the account may read them (the first release module leads, as the store's hero), the newest
// charts and releases, the Top 10s, and Beatport's curated playlists for the genre.
import type { MetadataItem, MetadataPage, PageBlock } from '@milkbeat/plugin-sdk';
import type { Chart, Genre, PageModule, Playlist, Release, Track } from '../api/types';
import { distinctBy } from '../util';
import { shelf, table } from './blocks';
import { chartItem, playlistItem, releaseItem } from './items';
import { featuredPage } from './owners';
import { refs } from './refs';
import { trackItem, trackItems } from './tracks';

export interface GenreFeatured {
  modules: PageModule[];
  charts: Chart[];
  releases: Release[];
  /** The genre's Top 100: its first ten are the Top 10, and its releases the Top 10 releases. */
  top: Track[];
  hypeTop: Track[];
  playlists: Playlist[];
}

const moduleTitle = (module: PageModule) => (module.name ?? '').replace(/\s*\(Mobile App\)\s*$/i, '').trim();

function moduleItems(module: PageModule, blockId: string): MetadataItem[] {
  const items = (module.items ?? []).map((entry) => {
    const item = entry.item;
    if (!item) return undefined;
    switch (entry.item_type?.name) {
      case 'release':
        return releaseItem(item, blockId);
      case 'chart':
        return chartItem(item, blockId);
      case 'track':
        return trackItem(item, blockId);
      default:
        return undefined;
    }
  });
  return distinctBy(items.filter((item): item is MetadataItem => item !== undefined), (item) => item.id);
}

function moduleShelf(module: PageModule): PageBlock | undefined {
  const id = `module/${module.id}`;
  return shelf(id, moduleTitle(module), moduleItems(module, id));
}

const holds = (type: string) => (module: PageModule) => (module.items ?? []).some((entry) => entry.item_type?.name === type);

/** The releases of the Top 100's tracks, in chart order: Beatport lists no genre's release chart. */
export function releasesOf(tracks: Track[]): Release[] {
  const releases = tracks
    .filter((track) => track.release)
    .map((track): Release => ({ ...(track.release as Release), artists: track.artists, new_release_date: track.new_release_date }));
  return distinctBy(releases, (release) => String(release.id));
}

export function genreFeaturedPage(genre: Genre, parts: GenreFeatured): MetadataPage {
  const name = genre.name ?? '';
  const releaseModules = parts.modules.filter(holds('release'));
  const otherModules = parts.modules.filter((module) => !holds('release')(module));
  const [hero, ...picks] = releaseModules;
  const topReleases = releasesOf(parts.top).slice(0, 10);
  return featuredPage('genre', genre, [
    hero ? moduleShelf(hero) : undefined,
    shelf('new-charts', `New ${name} Charts`, parts.charts.map((chart) => chartItem(chart, 'new-charts')), { showAll: refs.list('genre', genre.id, 'charts') }),
    ...otherModules.map(moduleShelf),
    shelf('latest-releases', `Latest ${name} Releases`, parts.releases.map((release) => releaseItem(release, 'latest-releases')), {
      showAll: refs.list('genre', genre.id, 'releases'),
    }),
    ...picks.map(moduleShelf),
    table('top-10', 'Beatport Top 10', trackItems(parts.top.slice(0, 10), 'top-10', 1), { showAll: refs.top('genre', genre.id) }),
    table('hype-top-10', 'Hype Top 10', trackItems(parts.hypeTop.slice(0, 10), 'hype-top-10', 1), { showAll: refs.top('genre', genre.id, true) }),
    table('top-releases', `Top 10 ${name} Releases`, topReleases.map((release, index) => releaseItem(release, 'top-releases', index + 1)), {
      showAll: refs.topReleases(genre.id),
    }),
    shelf('playlists', 'Beatport Playlists', parts.playlists.map((playlist) => playlistItem(playlist, 'curated', 'playlists')), {
      showAll: refs.list('genre', genre.id, 'playlists'),
    }),
  ]);
}
