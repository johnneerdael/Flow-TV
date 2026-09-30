// How Beatport's things map onto the contract's entity kinds. Tracks, releases (ALBUM) and artists
// keep Beatport's ids. What the contract has no kind for travels as a prefixed id: a label is a
// PROFILE `label:<id>`, a genre a MIX `genre:<id>`, and every playable list a PLAYLIST whose prefix
// says where its tracks live. Lists of releases, charts or playlists are MIX pages.
import type { EntityRef } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import type { PlaylistSource } from '../api/playlists';

export type Owner = 'genre' | 'artist' | 'label';
export type Tab = 'featured' | 'tracks' | 'releases' | 'charts' | 'playlists';
export type LibrarySection = 'playlists' | 'tracks' | 'artists' | 'labels';

/** What an entity ref points at, once decoded. */
export type Target =
  | { type: 'track'; id: string }
  | { type: 'release'; id: string }
  | { type: 'owner'; owner: Owner; id: string; tab?: Tab }
  | { type: 'chart'; id: string }
  | { type: 'playlist'; source: PlaylistSource; id: string }
  | { type: 'top'; scope: 'all' }
  | { type: 'top'; scope: Owner; id: string; hype?: boolean }
  | { type: 'topReleases'; genreId?: string }
  | { type: 'newCharts' }
  | { type: 'genres' }
  | { type: 'forYou' }
  | { type: 'library'; section: LibrarySection };

export const TABS: Record<Owner, Tab[]> = {
  genre: ['featured', 'tracks', 'releases', 'charts', 'playlists'],
  artist: ['featured', 'tracks', 'releases', 'charts'],
  label: ['featured', 'tracks', 'releases'],
};

const ref = (kind: EntityRef['kind'], providerId: string): EntityRef => ({ kind, providerId });

export const refs = {
  track: (id: number | string) => ref('TRACK', String(id)),
  release: (id: number | string) => ref('ALBUM', String(id)),
  artist: (id: number | string) => ref('ARTIST', String(id)),
  label: (id: number | string) => ref('PROFILE', `label:${id}`),
  genre: (id: number | string) => ref('MIX', `genre:${id}`),
  /** One tab of a genre, artist or label, opened as a page of its own. */
  list: (owner: Owner, id: number | string, tab: Exclude<Tab, 'featured'>) => ref('MIX', `${owner}:${id}:${tab}`),
  owner: (owner: Owner, id: number | string) =>
    owner === 'genre' ? refs.genre(id) : owner === 'label' ? refs.label(id) : refs.artist(id),
  chart: (id: number | string) => ref('PLAYLIST', `chart:${id}`),
  playlist: (source: PlaylistSource, id: number | string) => ref('PLAYLIST', `${source}:${id}`),
  top: (scope: 'all' | Owner, id?: number | string, hype = false) =>
    ref('PLAYLIST', scope === 'all' ? 'top:all' : `top:${scope}:${id}${hype ? ':hype' : ''}`),
  topReleases: (genreId?: number | string) => ref('MIX', genreId === undefined ? 'top:releases' : `top:genre:${genreId}:releases`),
  newCharts: () => ref('MIX', 'charts:new'),
  genres: () => ref('MIX', 'genres'),
  forYou: () => ref('MIX', 'for-you'),
  library: (section: LibrarySection) => ref('MIX', `my:${section}`),
};

const OWNERS: Owner[] = ['genre', 'artist', 'label'];
const SECTIONS: LibrarySection[] = ['playlists', 'tracks', 'artists', 'labels'];
const SOURCES: PlaylistSource[] = ['curated', 'mine'];
const isId = (value: string | undefined): value is string => !!value && /^\d+$/.test(value);

function decodeOwner(owner: Owner, parts: string[]): Target | undefined {
  const [, id, tab] = parts;
  if (!isId(id) || parts.length > 3) return undefined;
  if (tab === undefined) return { type: 'owner', owner, id };
  return TABS[owner].includes(tab as Tab) && tab !== 'featured' ? { type: 'owner', owner, id, tab: tab as Tab } : undefined;
}

function decodeTop(parts: string[]): Target | undefined {
  const [, scope, id, extra] = parts;
  if (scope === 'all' && parts.length === 2) return { type: 'top', scope: 'all' };
  if (scope === 'releases' && parts.length === 2) return { type: 'topReleases' };
  if (!OWNERS.includes(scope as Owner) || !isId(id) || parts.length > 4) return undefined;
  if (extra === 'releases') return scope === 'genre' ? { type: 'topReleases', genreId: id } : undefined;
  if (extra !== undefined && extra !== 'hype') return undefined;
  return { type: 'top', scope: scope as Owner, id, hype: extra === 'hype' };
}

const only = (found: Target | undefined, type: Target['type']) => (found?.type === type ? found : undefined);

function decodeTarget(entity: EntityRef): Target | undefined {
  const { kind, providerId } = entity;
  if (kind === 'TRACK') return isId(providerId) ? { type: 'track', id: providerId } : undefined;
  if (kind === 'ALBUM') return isId(providerId) ? { type: 'release', id: providerId } : undefined;
  if (kind === 'ARTIST') return isId(providerId) ? { type: 'owner', owner: 'artist', id: providerId } : undefined;
  const parts = providerId.split(':');
  const [prefix, id] = parts;
  if (kind === 'PROFILE') return prefix === 'label' && isId(id) && parts.length === 2 ? { type: 'owner', owner: 'label', id } : undefined;
  if (kind === 'PLAYLIST') {
    if (prefix === 'top') return only(decodeTop(parts), 'top');
    if (parts.length !== 2 || !isId(id)) return undefined;
    if (prefix === 'chart') return { type: 'chart', id };
    return SOURCES.includes(prefix as PlaylistSource) ? { type: 'playlist', source: prefix as PlaylistSource, id } : undefined;
  }
  if (kind !== 'MIX') return undefined;
  if (OWNERS.includes(prefix as Owner)) return decodeOwner(prefix as Owner, parts);
  if (prefix === 'top') return only(decodeTop(parts), 'topReleases');
  if (providerId === 'charts:new') return { type: 'newCharts' };
  if (providerId === 'genres') return { type: 'genres' };
  if (providerId === 'for-you') return { type: 'forYou' };
  if (prefix === 'my' && SECTIONS.includes(id as LibrarySection) && parts.length === 2) return { type: 'library', section: id as LibrarySection };
  return undefined;
}

/** What [entity] points at; NOT_FOUND for a ref this plugin never issued. */
export function target(entity: EntityRef): Target {
  return decodeTarget(entity) ?? fail('NOT_FOUND', `Beatport has no ${entity.kind} ${entity.providerId}`);
}
