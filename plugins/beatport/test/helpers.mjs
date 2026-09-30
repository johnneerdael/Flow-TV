// Offline plugin runs: Beatport's answers come from fixtures recorded by `npm run record`, one file per
// request, named by the request's path and query. Every request is kept so a test can check what was
// asked. A test can answer some requests itself with `routes`.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlugin } from '@milkbeat/mbplugin/harness';

const HERE = dirname(fileURLToPath(import.meta.url));
export const PLUGIN_DIR = join(HERE, '..');
export const FIXTURES = join(HERE, 'fixtures');

export const LIVE = process.env.LIVE === '1';

/** A session as the sign-in stores it; the token is made up. */
export const SESSION = { accessToken: 'fixture-token' };
export const SIGNED_IN = { session: JSON.stringify(SESSION) };

/**
 * The request's path and query, with its query sorted and today's date (in `publish_date=:<date>`,
 * which leaves out pre-orders) made constant, so a fixture recorded one day answers the next.
 */
export function normalized(url) {
  const parsed = new URL(url);
  const params = [...parsed.searchParams.entries()]
    .map(([key, value]) => [key, key === 'publish_date' ? value.replace(/\d{4}-\d{2}-\d{2}/, 'today') : value])
    .sort(([a], [b]) => a.localeCompare(b));
  const query = params.map(([key, value]) => `${key}=${value}`).join('&');
  return `${parsed.pathname}${query ? `?${query}` : ''}`;
}

/** The fixture file of a request: its path, readable, and a short hash of its query. */
export function slug(url) {
  const [path, query] = normalized(url).split('?');
  const name = path.replace(/^\/(v4\/)?/, '').replace(/\/$/, '').replace(/[^a-z0-9-]+/gi, '_');
  const hash = query ? `@${createHash('sha1').update(query).digest('hex').slice(0, 8)}` : '';
  return `${name}${hash}.json`;
}

export function fixture(url) {
  const file = join(FIXTURES, slug(url));
  if (!existsSync(file)) return undefined;
  return JSON.parse(readFileSync(file, 'utf8'));
}

/** The body of the fixture recorded for [url], for tests that check the mapping against it. */
export function recorded(url) {
  const found = fixture(url);
  if (!found) throw new Error(`No fixture for ${normalized(url)}`);
  return found.body;
}

export const API = 'https://api.beatport.com';

/**
 * The plugin, signed in unless `secrets` says otherwise. `routes` are `[match, response]` pairs:
 * `match(call)` sees `{ path, query, url, authorization }`; `response` is `{ status, body }` or a
 * function of the call answering one.
 */
export function offlinePlugin(options = {}) {
  const calls = [];
  const routes = options.routes ?? [];
  const plugin = loadPlugin(PLUGIN_DIR, {
    secrets: options.secrets ?? SIGNED_IN,
    http: async (request) => {
      const url = new URL(request.url);
      const call = { url: request.url, path: url.pathname, query: Object.fromEntries(url.searchParams), authorization: request.headers?.authorization };
      calls.push(call);
      const route = routes.find(([match]) => match(call));
      let answer = route ? route[1] : fixture(request.url);
      if (typeof answer === 'function') answer = answer(call);
      if (!answer) return { status: 404, url: request.url, headers: {}, body: JSON.stringify({ detail: `No fixture for ${normalized(request.url)}` }) };
      return { status: answer.status ?? 200, url: request.url, headers: answer.headers ?? {}, body: JSON.stringify(answer.body) };
    },
  });
  return { call: plugin.call, calls, plugin };
}

export const pathIs = (path) => (call) => call.path === path;

export const collections = (page) => page.blocks.filter((block) => block.type === 'collection');
export const block = (page, id) => {
  const found = page.blocks.find((candidate) => candidate.id === id);
  if (!found) throw new Error(`No block ${id} on ${page.id}: ${page.blocks.map((b) => b.id).join(', ')}`);
  return found;
};
export const header = (page) => page.blocks.find((candidate) => candidate.type === 'header');
