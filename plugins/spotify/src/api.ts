import { fail, mb } from '@milkbeat/plugin-sdk';
import { accessToken, generation } from './account';
import { queryHash } from './data';
import { array, at, object, text } from './util';
import type { JsonObject } from './util';

export const USER_AGENT = 'Mozilla/5.0 AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36';
const TTL_MS = 120_000;
const MAX_CACHE_ENTRIES = 40;
const cache = new Map<string, { data: JsonObject; expires: number }>();
const pending = new Map<string, Promise<JsonObject>>();
export function resetCatalog(): void { cache.clear(); pending.clear(); }

export async function queryWithToken(operation: string, variables: JsonObject, token: string, refreshHash = false): Promise<JsonObject> {
  const hash = await queryHash(operation, refreshHash);
  const response = await mb.http.fetch({
    url: 'https://api-partner.spotify.com/pathfinder/v2/query',
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'User-Agent': USER_AGENT, 'app-platform': 'WebPlayer', 'Accept-Language': (await mb.env.get()).locale },
    body: JSON.stringify({ operationName: operation, variables, extensions: { persistedQuery: { version: 1, sha256Hash: hash } } }),
    timeoutMs: 20_000,
  });
  if (response.status === 401) fail('SIGN_IN_EXPIRED', 'Spotify rejected the access token');
  if (response.status === 429) {
    const retry = Number(response.headers['retry-after'] ?? response.headers['Retry-After']);
    fail('RATE_LIMITED', 'Spotify is busy. Try again shortly.', { retryAfterMs: Number.isFinite(retry) && retry > 0 ? retry * 1000 : undefined });
  }
  if (response.status === 404) fail('NOT_FOUND', 'Spotify could not find this item');
  if (response.status !== 200) fail(response.status >= 500 ? 'NETWORK' : 'UNAVAILABLE', `Spotify returned HTTP ${response.status}`);
  let body: JsonObject;
  try { body = object(JSON.parse(response.body)); } catch { return fail('UNAVAILABLE', 'Spotify returned an invalid response'); }
  const errors = array(body.errors);
  if (errors.length > 0) {
    const missing = errors.some((e) => /persistedquerynotfound/i.test(text(at(e, 'message')) ?? '') || at(e, 'extensions', 'code') === 'PERSISTED_QUERY_NOT_FOUND');
    if (missing && !refreshHash) return queryWithToken(operation, variables, token, true);
    fail('UNAVAILABLE', missing ? 'Spotify query data needs an update. Try again later.' : 'Spotify could not load this page');
  }
  if (!body.data || typeof body.data !== 'object') fail('UNAVAILABLE', 'Spotify returned no metadata');
  return object(body.data);
}

export async function query(operation: string, variables: JsonObject): Promise<JsonObject> {
  const epoch = generation();
  const token = await accessToken();
  if (epoch !== generation()) fail('SIGN_IN_REQUIRED', 'The Spotify account changed');
  const key = JSON.stringify([epoch, operation, variables]);
  const cached = cache.get(key);
  if (cached && cached.expires > Date.now()) return cached.data;
  const active = pending.get(key);
  if (active) return active;
  const request = (async () => {
    let data: JsonObject;
    try { data = await queryWithToken(operation, variables, token); }
    catch (error) {
      if (object(error).code !== 'SIGN_IN_EXPIRED') throw error;
      data = await queryWithToken(operation, variables, await accessToken(token));
    }
    if (epoch !== generation()) fail('SIGN_IN_REQUIRED', 'The Spotify account changed');
    if (cache.size >= MAX_CACHE_ENTRIES) cache.delete(cache.keys().next().value as string);
    cache.set(key, { data, expires: Date.now() + TTL_MS });
    return data;
  })().finally(() => { if (pending.get(key) === request) pending.delete(key); });
  pending.set(key, request);
  return request;
}
