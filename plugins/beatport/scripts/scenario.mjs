// The plugin calls the fixtures are recorded from, and that the offline tests replay. The ids are
// public catalog entities chosen for their shape: a five-track EP, an artist with a DJ profile and
// charts, a chart longer than one page, a genre with curated playlists.
export const IDS = {
  release: '7452926',
  artist: '25648',
  label: '1328',
  genre: '90',
  chart: '904032',
  longChart: '901632',
  curated: '11446620',
  myPlaylist: '900001',
};

const entity = (kind, providerId, extra = {}) => ['metadata.entity', { entity: { kind, providerId }, ...extra }];
const tracks = (kind, providerId, extra = {}) => ['metadata.tracks', { entity: { kind, providerId }, ...extra }];

export const SCENARIO = [
  ['metadata.home', {}],
  ['metadata.search', { query: 'solomun' }],
  ['metadata.search', { query: 'solomun', filterId: 'tracks' }],
  ['metadata.search', { query: 'solomun', filterId: 'tracks', cursor: '2' }],
  ['metadata.search', { query: 'shortlist', filterId: 'playlists' }],
  entity('ALBUM', IDS.release),
  entity('ARTIST', IDS.artist),
  entity('ARTIST', IDS.artist, { filterId: 'charts' }),
  entity('MIX', `artist:${IDS.artist}:releases`),
  entity('MIX', `artist:${IDS.artist}:releases`, { cursor: '2' }),
  entity('PROFILE', `label:${IDS.label}`),
  entity('PROFILE', `label:${IDS.label}`, { filterId: 'tracks' }),
  entity('MIX', `genre:${IDS.genre}`),
  entity('MIX', `genre:${IDS.genre}:playlists`),
  entity('MIX', `genre:${IDS.genre}:playlists`, { cursor: '2' }),
  entity('MIX', `genre:${IDS.genre}`, { filterId: 'charts' }),
  entity('PLAYLIST', `chart:${IDS.chart}`),
  entity('PLAYLIST', `chart:${IDS.longChart}`),
  entity('PLAYLIST', `chart:${IDS.longChart}`, { cursor: '2' }),
  entity('PLAYLIST', `curated:${IDS.curated}`),
  entity('PLAYLIST', 'top:all'),
  entity('PLAYLIST', `top:genre:${IDS.genre}:hype`),
  entity('MIX', 'top:releases'),
  entity('MIX', `top:genre:${IDS.genre}:releases`),
  entity('MIX', 'charts:new'),
  entity('MIX', 'genres'),
  entity('MIX', 'for-you'),
  entity('PLAYLIST', `mine:${IDS.myPlaylist}`),
  tracks('ALBUM', IDS.release),
  tracks('PLAYLIST', `chart:${IDS.longChart}`),
  tracks('PLAYLIST', `chart:${IDS.longChart}`, { cursor: '2' }),
  tracks('PLAYLIST', `curated:${IDS.curated}`),
  tracks('MIX', `genre:${IDS.genre}`),
  tracks('MIX', 'my:tracks'),
  ['metadata.library', {}],
  ['metadata.library', { section: 'playlists' }],
  ['metadata.library', { section: 'tracks' }],
  ['metadata.library', { section: 'tracks', cursor: '2' }],
  ['metadata.library', { section: 'artists' }],
  ['metadata.library', { section: 'labels' }],
];
