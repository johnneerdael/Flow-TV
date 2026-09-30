// Records the offline tests' fixtures: runs the built plugin through SCENARIO against live Beatport
// and keeps every answer, trimmed. Nothing personal is recorded: the listener's own endpoints (my/*)
// and the personal picks are never fetched here, but made up from public catalog data. Run it with
// `npm run record`; it needs the owner's Beatport credentials (see browse-session.mjs).
import { mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { liveHttp, loadPlugin } from '@milkbeat/mbplugin/harness';
import { FIXTURES, PLUGIN_DIR, normalized, slug } from '../test/helpers.mjs';
import { browseSession } from './browse-session.mjs';
import { SCENARIO } from './scenario.mjs';
import { synthesize } from './synthesize.mjs';

if (process.env.RECORD !== '1') {
  console.error('Set RECORD=1 to record fixtures against live Beatport.');
  process.exit(1);
}

const RESULTS_KEPT = 4;
const MODULE_ITEMS_KEPT = 3;
/** Fields the plugin never reads, and every field that could name or identify a person. */
const DROPPED = new Set([
  'add_by', 'available_worldwide', 'bsrc_remixer', 'change_by', 'change_date', 'created', 'created_person_id', 'current_status',
  'default_pre_order_weeks', 'email', 'enabled', 'encode_status', 'encoded_date', 'exclusive', 'first_name', 'free_download_end_date',
  'free_download_start_date', 'free_downloads', 'grid', 'has_exclusive_contract', 'hype_active', 'hype_eligible', 'hype_trial_end_date',
  'hype_trial_start_date', 'is_approved', 'is_available_for_hype', 'is_available_for_pre_order', 'is_dj_edit', 'is_dj_version', 'is_hype',
  'is_indexed', 'is_published', 'is_ugc_remix', 'label_track_identifier', 'last_name', 'latest_active_publish_date', 'launch_date', 'length',
  'override_price', 'owner_person', 'person_id', 'phone_number', 'pre_order', 'pre_order_date', 'price', 'price_override_firm', 'publish_status',
  'sale_type', 'sample_end_ms', 'sample_start_ms', 'sample_url', 'slug', 'upc', 'updated', 'updated_person_id', 'url', 'user_id',
  'username', 'website',
]);

/** [node] without the dropped fields, its lists cut to [kept] entries. */
function trim(node, kept = RESULTS_KEPT, depth = 0) {
  if (Array.isArray(node)) return node.map((child) => trim(child, kept, depth + 1));
  if (!node || typeof node !== 'object') return node;
  const out = {};
  for (const [key, value] of Object.entries(node)) {
    if (DROPPED.has(key)) continue;
    if (key === 'tracks' && Array.isArray(value) && typeof value[0] === 'string') continue;
    let child = value;
    if (key === 'results' && Array.isArray(value) && depth === 0) child = value.slice(0, kept);
    if (key === 'items' && Array.isArray(value)) child = value.slice(0, MODULE_ITEMS_KEPT);
    if (depth === 0 && 'order' in node && ['tracks', 'artists', 'releases', 'labels', 'charts', 'playlists'].includes(key) && Array.isArray(value)) child = value.slice(0, kept);
    out[key] = trim(child, kept, depth + 1);
  }
  return out;
}

const PRIVATE = [/^\/v4\/my\//, /^\/catalog\/v1\//];

const session = await browseSession();
const network = ['api.beatport.com', 'account.beatport.com', '*.beatport.com'];
const publicGet = async (path) => {
  const response = await liveHttp({ url: `https://api.beatport.com${path}`, headers: { authorization: `Bearer ${session.accessToken}` } }, network);
  return trim(JSON.parse(response.body));
};

const recorded = new Map();
const plugin = loadPlugin(PLUGIN_DIR, {
  secrets: { session: JSON.stringify({ accessToken: 'fixture-token' }) },
  http: async (request) => {
    const url = new URL(request.url);
    let status;
    let body;
    if (PRIVATE.some((pattern) => pattern.test(url.pathname))) {
      ({ status, body } = await synthesize(url, publicGet));
    } else {
      const response = await liveHttp({ ...request, headers: { ...request.headers, authorization: `Bearer ${session.accessToken}` } }, network);
      status = response.status;
      // Genres are read once and looked up by id, so all of them are kept.
      body = trim(JSON.parse(response.body || 'null'), url.pathname === '/v4/catalog/genres/' ? Number.POSITIVE_INFINITY : RESULTS_KEPT);
    }
    recorded.set(slug(request.url), { url: normalized(request.url), status, body });
    return { status, url: request.url, headers: {}, body: JSON.stringify(body) };
  },
});

for (const [path, request] of SCENARIO) {
  try {
    await plugin.call(path, request);
    console.log(`recorded ${path} ${JSON.stringify(request)}`);
  } catch (error) {
    console.log(`${path} ${JSON.stringify(request)} failed: ${error.code}`);
  }
}

mkdirSync(FIXTURES, { recursive: true });
for (const file of readdirSync(FIXTURES)) if (file.endsWith('.json')) rmSync(join(FIXTURES, file));
for (const [file, { url, status, body }] of [...recorded].sort(([a], [b]) => a.localeCompare(b))) {
  writeFileSync(join(FIXTURES, file), `${JSON.stringify({ url, status, body }, null, 1)}\n`);
}
console.log(`${recorded.size} fixtures written to ${FIXTURES}`);
