// The main site's endpoints the video pages read, all as the anonymous WEB client, as the app did.
import { fail, mb, PluginFailure } from '@milkbeat/plugin-sdk';
import { USER_AGENT_WEB, WEB } from '../innertube/clients';
import { innertube } from '../innertube/request';
import { locale } from '../innertube/session';
import { type Json, query } from '../util';

const www = (endpoint: string, body: Json): Promise<Json> => innertube(endpoint, { client: WEB, site: 'www', body });

/** A search page; [params] filters it, and a continuation carries its filter along. */
export const search = (text: string, params?: string, continuation?: string): Promise<Json> =>
  www('search', continuation ? { continuation } : { query: text, params });

/** A channel or playlist; YouTube answers an id it does not know with a 400, which is a missing page, not a network fault. */
export async function browse(browseId: string, params?: string): Promise<Json> {
  try {
    return await www('browse', { browseId, params });
  } catch (error) {
    if (error instanceof PluginFailure && error.code === 'NETWORK' && / answered 40[04]$/.test(error.message)) {
      fail('NOT_FOUND', `No page ${browseId}`);
    }
    throw error;
  }
}

export const browseContinuation = (continuation: string): Promise<Json> => www('browse', { continuation });

/** The watch page of a video (related videos, comment section) or of a playlist, or one of its continuations. */
export const next = (body: { videoId?: string; playlistId?: string; continuation?: string }): Promise<Json> => www('next', body);

export const liveChat = (continuation: string): Promise<Json> => www('live_chat/get_live_chat', { continuation });

/** The typeahead host answers JSONP: `window.google.ac.h([query, [[text, …], …]])`. */
export async function suggestions(text: string): Promise<string> {
  const { hl, gl } = await locale();
  const response = await mb.http.fetch({
    url: `https://www.youtube.com/complete/search?${query({ client: 'youtube', ds: 'yt', hl, gl, q: text })}`,
    headers: { 'user-agent': USER_AGENT_WEB },
  });
  if (response.status < 200 || response.status >= 300) fail('NETWORK', `complete/search answered ${response.status}`);
  return response.body;
}
