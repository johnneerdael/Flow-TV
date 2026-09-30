// A genre's Featured tab, laid out as the web store's genre page: its banner carousel, new charts and
// latest releases, hype and staff picks (Beatport's editorial modules, when the account may read
// them; the catalog's newest charts and releases otherwise), the Top 10s, and the curated playlists.
import type { EntityRef, MetadataItem, MetadataPage } from '@milkbeat/plugin-sdk';
import type { Chart, Genre, PageModule, PageModuleItem, Playlist, Release, Track } from '../api/types';
import { distinctBy, isPresent } from '../util';
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

/** "Melodic Hype Picks: Week 39" on Melodic House & Techno reads "Hype Picks", as the store shows it. */
export function moduleTitle(module: PageModule, genreName: string): string {
  const name = (module.name ?? '')
    .replace(/\s*\(Mobile App\)\s*$/i, '')
    .replace(/\s*:\s*Week\s+\d+\s*$/i, '')
    .trim();
  const [first, ...rest] = name.split(/\s+/);
  return rest.length > 0 && genreName.toLowerCase().startsWith(first.toLowerCase()) ? rest.join(' ') : name;
}

const LINKS: [RegExp, (id: string) => EntityRef][] = [
  [/\/genre\/[^/]+\/(\d+)/, refs.genre],
  [/\/release\/[^/]+\/(\d+)/, refs.release],
  [/\/chart\/[^/]+\/(\d+)/, refs.chart],
  [/\/artist\/[^/]+\/(\d+)/, refs.artist],
  [/\/label\/[^/]+\/(\d+)/, refs.label],
];

/** The page a banner's store link opens, when it is one the plugin has. */
function linked(url: string | null | undefined): EntityRef | undefined {
  for (const [pattern, ref] of LINKS) {
    const match = url ? pattern.exec(url) : null;
    if (match) return ref(match[1]);
  }
  return undefined;
}

function entryItem(entry: PageModuleItem, blockId: string): MetadataItem | undefined {
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
}

function moduleItems(module: PageModule, blockId: string): MetadataItem[] {
  const items = (module.items ?? []).map((entry) => entryItem(entry, blockId));
  return distinctBy(items.filter(isPresent), (item) => item.id);
}

/** The store's banner carousel: its wide pictures, each opening what it features. */
function bannerItems(module: PageModule, blockId: string): MetadataItem[] {
  const items = (module.items ?? []).map((entry, index): MetadataItem | undefined => {
    const featured = entryItem(entry, blockId);
    const entity = featured?.entity ?? linked(entry.external_url);
    if (!entity) return undefined;
    const picture = entry.image?.uri ? { url: entry.image.uri } : featured?.artwork;
    return { ...(featured ?? { id: `${blockId}#${index}`, title: '' }), entity, artwork: picture, view: 'LANDSCAPE_CARD' };
  });
  return distinctBy(items.filter(isPresent), (item) => item.id);
}

const ofType = (modules: PageModule[], type: string) => modules.filter((module) => module.type?.name === type);
const isBanner = (module: PageModule) => /slideshow/i.test(module.type?.name ?? '');
const isNewReleases = (module: PageModule) => /new releases/i.test(module.name ?? '');

/** The releases of the Top 100's tracks, in chart order: Beatport lists no genre's release chart. */
export function releasesOf(tracks: Track[]): Release[] {
  const releases = tracks
    .filter((track) => track.release)
    .map((track): Release => ({ ...(track.release as Release), artists: track.artists, new_release_date: track.new_release_date }));
  return distinctBy(releases, (release) => String(release.id));
}

export function genreFeaturedPage(genre: Genre, parts: GenreFeatured): MetadataPage {
  const name = genre.name ?? '';
  const modules = parts.modules;
  const banners = modules.find(isBanner);
  const chartModule = ofType(modules, 'chartFeature')[0];
  const releaseModules = ofType(modules, 'releaseFeature');
  const newReleases = releaseModules.find(isNewReleases);
  const picks = [...ofType(modules, 'hypeFeature'), ...releaseModules.filter((module) => module !== newReleases)];
  const moduleShelf = (module: PageModule) => {
    const id = `module/${module.id}`;
    return shelf(id, moduleTitle(module, name), moduleItems(module, id));
  };
  const topReleases = releasesOf(parts.top).slice(0, 10);
  return featuredPage('genre', genre, [
    banners ? shelf('banners', '', bannerItems(banners, 'banners')) : undefined,
    shelf(
      'new-charts',
      `New ${name} Charts`,
      chartModule ? moduleItems(chartModule, 'new-charts') : parts.charts.map((chart) => chartItem(chart, 'new-charts')),
      { showAll: refs.list('genre', genre.id, 'charts') },
    ),
    shelf(
      'latest-releases',
      `Latest ${name} Releases`,
      newReleases ? moduleItems(newReleases, 'latest-releases') : parts.releases.map((release) => releaseItem(release, 'latest-releases')),
      { showAll: refs.list('genre', genre.id, 'releases') },
    ),
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
