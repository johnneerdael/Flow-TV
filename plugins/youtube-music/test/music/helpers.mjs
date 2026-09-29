// Offline plugin runs for the metadata tests: InnerTube calls are answered from recorded fixtures,
// matched on endpoint and body, and every call is kept so a test can check what was asked.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlugin } from '@milkbeat/mbplugin/harness';

const HERE = dirname(fileURLToPath(import.meta.url));
export const PLUGIN_DIR = join(HERE, '..', '..');
const FIXTURES = join(HERE, '..', 'fixtures', 'music');

export const LIVE = process.env.LIVE === '1';

export function fixture(name) {
  return JSON.parse(readFileSync(join(FIXTURES, `${name}.json`), 'utf8'));
}

const SW_JS_DATA = `)]}'\n${JSON.stringify([[null, null, ['CgtGSVhUVVJFMA%3D%3D']]])}`;

/** A session as the sign-in stores it; the cookie is made up. */
export const SIGNED_IN = { session: JSON.stringify({ cookie: 'SAPISID=fixture-sapisid; SID=fixture-sid', dataSyncId: 'fixture-sync' }) };

const ACCOUNT_MENU = {
  actions: [{ openPopupAction: { popup: { multiPageMenuRenderer: { header: { activeAccountHeaderRenderer: { accountName: { runs: [{ text: 'Listener' }] } } } } } } }],
};

/**
 * The plugin with [routes]: `[match, response]` pairs, where `match(call)` sees
 * `{ endpoint, body, query, signed }` and `response` is a fixture name, a JSON value, or a function of
 * the call. The account menu answers signed-in checks unless a route overrides it.
 */
export function offlinePlugin(routes, options = {}) {
  const calls = [];
  const plugin = loadPlugin(PLUGIN_DIR, {
    secrets: options.secrets ?? {},
    http: async (request) => {
      if (request.url === 'https://music.youtube.com/sw.js_data') return { status: 200, url: request.url, headers: {}, body: SW_JS_DATA };
      const url = new URL(request.url);
      const body = request.body ? JSON.parse(request.body) : {};
      const call = {
        endpoint: url.pathname.replace('/youtubei/v1/', ''),
        body,
        query: Object.fromEntries(url.searchParams),
        signed: typeof request.headers?.authorization === 'string' && request.headers.authorization.startsWith('SAPISIDHASH'),
      };
      calls.push(call);
      const route = routes.find(([match]) => match(call));
      let response = route?.[1];
      if (response === undefined && call.endpoint === 'account/account_menu') response = ACCOUNT_MENU;
      if (response === undefined) return { status: 404, url: request.url, headers: {}, body: '{}' };
      if (typeof response === 'function') response = response(call);
      if (typeof response === 'string') response = fixture(response);
      return { status: 200, url: request.url, headers: {}, body: JSON.stringify(response) };
    },
  });
  return { call: plugin.call, calls, plugin };
}

export const browseOf = (browseId) => (call) => call.endpoint === 'browse' && call.body.browseId === browseId;
export const continuationOf = (token) => (call) => call.body.continuation === token || call.query.continuation === token;
export const nextOf = (fields) => (call) =>
  call.endpoint === 'next' && Object.entries(fields).every(([key, value]) => call.body[key] === value);

export const blocks = (page) => page.blocks.filter((block) => block.type === 'collection');
export const collection = (page, title) => {
  const found = blocks(page).filter((block) => block.header?.title === title);
  if (found.length !== 1) throw new Error(`Expected one collection titled ${title}, found ${found.length}`);
  return found[0];
};
export const header = (page) => page.blocks.find((block) => block.type === 'header');
