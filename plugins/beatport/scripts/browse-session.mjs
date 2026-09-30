// For live tests and fixture recording only: signs in the way beets-beatport4 does, with the public
// client of Beatport's API docs, and answers the plugin's Session. The credentials are read from
// BEATPORT_ENV (default: the owner's .secrets/beatport.env) and never printed, logged or stored.
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const API = 'https://api.beatport.com/v4';
const REDIRECT_URI = `${API}/auth/o/post-message/`;
const DEFAULT_ENV = join(homedir(), 'Scripts', 'TV-Streaming', '.secrets', 'beatport.env');

function credentials() {
  const text = readFileSync(process.env.BEATPORT_ENV ?? DEFAULT_ENV, 'utf8');
  const env = {};
  for (const line of text.split('\n')) {
    const index = line.indexOf('=');
    if (index > 0) env[line.slice(0, index).trim()] = line.slice(index + 1).trim().replace(/^['"]|['"]$/g, '');
  }
  if (!env.BEATPORT_USERNAME || !env.BEATPORT_PASSWORD) throw new Error('beatport.env has no BEATPORT_USERNAME or BEATPORT_PASSWORD');
  return { username: env.BEATPORT_USERNAME, password: env.BEATPORT_PASSWORD };
}

/** The docs page's scripts carry `API_CLIENT_ID: '…'`, the public client the docs sign in with. */
async function docsClientId() {
  const page = await (await fetch(`${API}/docs/`)).text();
  const scripts = [...page.matchAll(/src="([^"]+\.js)"/g)].map((match) => new URL(match[1], `${API}/docs/`).href);
  for (const script of scripts) {
    const found = (await (await fetch(script)).text()).match(/API_CLIENT_ID:\s*'([^']+)'/);
    if (found) return found[1];
  }
  throw new Error('No API_CLIENT_ID in the docs scripts');
}

/** Signs in and answers `{ accessToken, expiresAt }`, as the plugin keeps its session. */
export async function browseSession() {
  const clientId = await docsClientId();
  const login = await fetch(`${API}/auth/login/`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(credentials()),
  });
  if (!login.ok) throw new Error(`Beatport login answered ${login.status}`);
  const cookie = login.headers
    .getSetCookie()
    .map((value) => value.split(';')[0])
    .join('; ');
  const authorize = await fetch(`${API}/auth/o/authorize/?response_type=code&client_id=${encodeURIComponent(clientId)}&redirect_uri=${encodeURIComponent(REDIRECT_URI)}`, {
    headers: { cookie },
    redirect: 'manual',
  });
  const location = authorize.headers.get('location');
  const code = location ? new URL(location, API).searchParams.get('code') : null;
  if (!code) throw new Error(`Beatport authorize answered ${authorize.status} without a code`);
  const params = new URLSearchParams({ code, grant_type: 'authorization_code', redirect_uri: REDIRECT_URI, client_id: clientId });
  const token = await fetch(`${API}/auth/o/token/?${params}`, { method: 'POST' });
  const body = await token.json();
  if (!body.access_token) throw new Error(`Beatport token answered ${token.status}`);
  return { accessToken: body.access_token, expiresAt: Date.now() + (body.expires_in ?? 3600) * 1000 - 60_000 };
}
