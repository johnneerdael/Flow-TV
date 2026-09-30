// Every Beatport request: sent with the account's bearer token, and Beatport's statuses turned into
// the host's errors. A refused token is handed back to the token source once before giving up.
import { fail, mb } from '@milkbeat/plugin-sdk';
import { tokenSource } from '../account';
import { query } from '../util';

export const API_HOST = 'https://api.beatport.com';
const V4 = `${API_HOST}/v4/`;

export type Params = Record<string, string | number | boolean | undefined>;

export interface RequestOptions {
  /**
   * Whether a 401 means the session is over. The recommendations service outside /v4 refuses tokens
   * /v4 accepts, so its 401 only makes that one part unavailable.
   */
  expiresSession?: boolean;
}

function urlOf(path: string, params: Params): string {
  const base = path.startsWith('/') ? `${API_HOST}${path}` : `${V4}${path}`;
  const search = query(params);
  return search ? `${base}?${search}` : base;
}

function failFor(status: number, url: string, retryAfter: string | undefined, options: RequestOptions): never {
  const path = url.slice(API_HOST.length).split('?')[0];
  if (status === 401) {
    if (options.expiresSession === false) fail('UNAVAILABLE', `Beatport refused the account's token for ${path}`);
    fail('SIGN_IN_EXPIRED', 'Beatport no longer accepts this sign-in');
  }
  if (status === 403) fail('UNAVAILABLE', `Beatport does not allow this account ${path}`);
  if (status === 404) fail('NOT_FOUND', `Beatport has nothing at ${path}`);
  if (status === 429) {
    const seconds = Number(retryAfter);
    fail('RATE_LIMITED', 'Beatport asked to slow down', { retryAfterMs: Number.isFinite(seconds) && seconds > 0 ? seconds * 1000 : undefined });
  }
  fail(status >= 500 ? 'NETWORK' : 'UNAVAILABLE', `Beatport answered ${status} for ${path}`);
}

async function send(url: string, token: string, body: unknown) {
  const headers: Record<string, string> = { authorization: `Bearer ${token}`, accept: 'application/json' };
  if (body === undefined) return mb.http.fetch({ url, headers });
  return mb.http.fetch({ url, method: 'POST', headers: { ...headers, 'content-type': 'application/json' }, body: JSON.stringify(body) });
}

async function request<T>(path: string, params: Params, options: RequestOptions, body?: unknown): Promise<T> {
  const source = tokenSource();
  const token = await source.token();
  if (!token) fail('SIGN_IN_REQUIRED', 'Beatport needs a signed-in account');
  const url = urlOf(path, params);
  let response = await send(url, token, body);
  if (response.status === 401 && options.expiresSession !== false) {
    const retry = await source.rejected(token);
    if (retry) response = await send(url, retry, body);
  }
  if (response.status < 200 || response.status >= 300) failFor(response.status, url, response.headers['retry-after'], options);
  try {
    return JSON.parse(response.body) as T;
  } catch {
    return fail('UNAVAILABLE', `Beatport answered ${url.slice(API_HOST.length).split('?')[0]} with something other than JSON`);
  }
}

/** GETs [path] (relative to /v4/, or absolute from the host when it starts with a slash) as the account. */
export function get<T>(path: string, params: Params = {}, options: RequestOptions = {}): Promise<T> {
  return request<T>(path, params, options);
}

/** POSTs [body] as JSON to [path] as the account. */
export function post<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, {}, {}, body);
}
