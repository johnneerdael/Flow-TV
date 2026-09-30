import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { loadPlugin } from '@milkbeat/mbplugin/harness';

export const DIR = fileURLToPath(new URL('..', import.meta.url));
export const fixture = (name) => JSON.parse(readFileSync(new URL(`fixtures/${name}.json`, import.meta.url), 'utf8'));
export const SESSION = {
  accessToken: 'fixture-token',
  expiresAt: Date.now() + 3_600_000,
  cookies: 'sp_dc=fixture-cookie',
  accountKey: 'fixture-account',
  name: 'Fixture Listener',
};
export const ARTIST = { kind: 'ARTIST', providerId: 'spotify:artist:2IelDQgKvM3U4P7HVVmaOX' };
export const ALBUM = { kind: 'ALBUM', providerId: 'spotify:album:0gNodTZAdNht0OpLirkGBW' };
export const PLAYLIST = { kind: 'PLAYLIST', providerId: 'spotify:playlist:37i9dQZF1DX28LbavzGUyw' };
export const LIKED = { kind: 'PLAYLIST', providerId: 'spotify:collection:tracks' };
export const KEY = Buffer.from('12345678901234567890').toString('hex');

export function offline(options = {}) {
  const calls = [];
  const routes = options.routes ?? [];
  const plugin = loadPlugin(DIR, {
    secrets: options.secrets ?? { session: JSON.stringify(SESSION) },
    hostOverrides: options.hostOverrides,
    http: async (request) => {
      const url = new URL(request.url);
      const body = request.body ? JSON.parse(request.body) : {};
      const call = { ...request, path: url.pathname, query: Object.fromEntries(url.searchParams), op: body.operationName, variables: body.variables, hash: body.extensions?.persistedQuery?.sha256Hash };
      calls.push(call);
      const route = routes.find(([match]) => match(call));
      let answer = route?.[1];
      if (typeof answer === 'function') answer = await answer(call);
      if (answer === undefined) {
        const name = { home: 'home', queryArtistOverview: 'artist', getAlbum: 'album', fetchPlaylist: 'playlist', searchDesktop: 'search', getTrack: 'track' }[call.op];
        answer = name ? { body: fixture(name) } : { status: 404, body: {} };
      }
      return { status: answer.status ?? 200, headers: answer.headers ?? {}, url: request.url, body: JSON.stringify(answer.body) };
    },
  });
  return { ...plugin, calls };
}

export const op = (name) => (call) => call.op === name;
export const path = (value) => (call) => call.path === value;
export const collections = (page) => page.blocks.filter((block) => block.type === 'collection');
export const table = (page) => collections(page).find((block) => block.layout === 'TRACK_TABLE');
export const header = (page) => page.blocks.find((block) => block.type === 'header');
export const deferred = () => {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
};

export const renewalRoutes = (tokenAnswer = { accessToken: 'renewed', accessTokenExpirationTimestampMs: Date.now() + 3_600_000, isAnonymous: false }) => [
  [path('/johnneerdael/Milkbeat/spotify-data/totp.json'), { body: [{ v: 1, keyHex: KEY }] }],
  [path('/api/server-time'), { body: { serverTime: 59 } }],
  [path('/api/token'), typeof tokenAnswer === 'function' ? tokenAnswer : { body: tokenAnswer }],
  [op('profileAttributes'), { body: { data: { me: { profile: { name: 'Fixture Listener', uri: 'spotify:user:fixture' } } } } }],
];
