// The InnerTube calls the metadata role makes, all as YouTube Music's web client. Whether a call runs
// as the signed-in listener follows the app: pages, library and mixes do; search does not.
import { fail } from '@milkbeat/plugin-sdk';
import { WEB_REMIX } from '../innertube/clients';
import { innertube } from '../innertube/request';
import { markExpired, usableSession } from '../innertube/session';
import { type Json, dig } from '../util';

export interface WatchEndpoint {
  videoId?: string;
  playlistId?: string;
  playlistSetVideoId?: string;
  index?: number;
  params?: string;
}

export async function signedIn(): Promise<boolean> {
  return (await usableSession()) !== undefined;
}

/** A browse, as the listener when signed in. */
export function browse(request: { browseId?: string; params?: string; continuation?: string }, auth = true): Promise<Json> {
  const body = request.continuation ? { continuation: request.continuation } : { browseId: request.browseId, params: request.params };
  return innertube('browse', { client: WEB_REMIX, auth, body });
}

/** A search, or its continuation; always anonymous, as the app searched. */
export function search(request: { query?: string; params?: string; continuation?: string }): Promise<Json> {
  if (request.continuation) {
    return innertube('search', { client: WEB_REMIX, params: { continuation: request.continuation, ctoken: request.continuation } });
  }
  return innertube('search', { client: WEB_REMIX, body: { query: request.query, params: request.params } });
}

export function suggestions(input: string): Promise<Json> {
  return innertube('music/get_search_suggestions', { client: WEB_REMIX, body: { input } });
}

/** The watch queue of [endpoint], as the listener when [auth] and signed in. */
export function next(endpoint: WatchEndpoint, continuation: string | undefined, auth: boolean): Promise<Json> {
  return innertube('next', {
    client: WEB_REMIX,
    auth,
    body: {
      videoId: endpoint.videoId,
      playlistId: endpoint.playlistId,
      playlistSetVideoId: endpoint.playlistSetVideoId,
      index: endpoint.index,
      params: endpoint.params,
      continuation,
    },
  });
}

let verifiedCookie: string | undefined;

/**
 * Fails with SIGN_IN_EXPIRED when the account's cookie no longer signs in: YouTube Music answers a
 * dead cookie with a generic signed-out home rather than an error, so the account is checked first,
 * once per cookie, as the app did.
 */
export async function verifyAccount(): Promise<void> {
  const session = await usableSession();
  if (!session) fail('SIGN_IN_REQUIRED', 'Not signed in');
  if (verifiedCookie === session.cookie) return;
  const menu = await innertube('account/account_menu', { client: WEB_REMIX, auth: true });
  const header = dig(menu, 'actions', 0, 'openPopupAction', 'popup', 'multiPageMenuRenderer', 'header', 'activeAccountHeaderRenderer');
  if (!header) {
    await markExpired();
    fail('SIGN_IN_EXPIRED', 'YouTube Music no longer knows this account');
  }
  verifiedCookie = session.cookie;
}
