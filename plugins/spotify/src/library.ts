import type { LibraryRequest, MetadataPage } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { requireAccount } from './account';
import { query } from './api';
import { required } from './catalog';
import { LIKED, cards } from './mapping';
import { entity } from './entity';
import { collection, present } from './pages';
import { at, items, nextCursor, offset } from './util';

const FILTERS = [
  { id: 'all', label: 'All' }, { id: 'playlists', label: 'Playlists' },
  { id: 'artists', label: 'Artists' }, { id: 'albums', label: 'Albums' }, { id: 'liked', label: 'Liked Songs' },
];
const TYPES: Record<string, string[]> = { all: [], playlists: ['Playlists'], artists: ['Artists'], albums: ['Albums'] };

export async function library(request: LibraryRequest): Promise<MetadataPage> {
  await requireAccount();
  const section = request.section ?? 'all';
  if (!FILTERS.some((f) => f.id === section)) fail('UNSUPPORTED', 'Unsupported Spotify Library filter');
  if (section === 'liked') return { ...await entity({ entity: LIKED, cursor: request.cursor }), filters: { options: FILTERS } };
  const scope = `library/${section}`;
  const start = offset(request.cursor, scope);
  const data = required(at(await query('libraryV3', {
    filters: TYPES[section], order: null, textFilter: '', features: ['LIKED_SONGS'], limit: 50, offset: start,
    flatten: true, expandedFolders: [], folderUri: null, includeFoldersWhenFlattening: false,
  }), 'me', 'libraryV3'));
  const values = cards(items(data));
  if (start === 0 && ['all', 'playlists'].includes(section) && !values.some((v) => v.entity.providerId === LIKED.providerId)) {
    values.unshift({ id: LIKED.providerId, entity: LIKED, title: 'Liked Songs', subtitle: 'Your saved songs', view: 'COVER_CARD' });
  }
  return { id: 'spotify/library', blocks: present([collection('library', undefined, values)]), filters: { options: FILTERS }, nextCursor: nextCursor(scope, start, items(data).length, data.totalCount) };
}
