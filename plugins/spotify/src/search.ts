import type { MetadataPage, SearchRequest } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { query } from './api';
import { required } from './catalog';
import { cards } from './mapping';
import { collection, present } from './pages';
import { at, items, nextCursor, object, offset } from './util';

const FILTERS = [
  { id: 'all', label: 'All' }, { id: 'tracks', label: 'Songs' }, { id: 'artists', label: 'Artists' },
  { id: 'albums', label: 'Albums' }, { id: 'playlists', label: 'Playlists' },
];
const KEYS: Record<string, string> = { tracks: 'tracksV2', artists: 'artists', albums: 'albumsV2', playlists: 'playlists' };

export async function search(request: SearchRequest): Promise<MetadataPage> {
  const filter = request.filterId ?? 'all';
  if (!FILTERS.some((f) => f.id === filter)) fail('UNSUPPORTED', 'Unsupported Spotify Search filter');
  const term = request.query.trim();
  const scope = `search/${JSON.stringify([term, filter])}`;
  const start = offset(request.cursor, scope);
  if (!term) return { id: 'spotify/search', blocks: [], filters: { options: FILTERS } };
  const data = required(at(await query('searchDesktop', {
    searchTerm: term, offset: start, limit: 20, numberOfTopResults: 5, includeAudiobooks: false,
    includeArtistHasConcertsField: false, includePreReleases: false, includeLocalConcertsField: false, includeAuthors: false,
  }), 'searchV2'));
  const selected = FILTERS.filter((f) => f.id !== 'all' && (filter === 'all' || f.id === filter));
  const blocks = selected.map((f) => {
    const values = cards(items(data[KEYS[f.id]]));
    return collection(f.id, f.label, values, f.id === 'tracks');
  });
  const section = object(data[KEYS[filter]]);
  return { id: 'spotify/search', blocks: present(blocks), filters: { options: FILTERS }, nextCursor: filter !== 'all' ? nextCursor(scope, start, items(section).length, section.totalCount) : undefined };
}
