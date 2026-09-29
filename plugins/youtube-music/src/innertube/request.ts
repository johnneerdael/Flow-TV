// One InnerTube call: the client's identity and headers, the context body, the account's headers
// when signed in, and YouTube's failures mapped to the plugin error codes the host understands.
import { fail, mb } from '@milkbeat/plugin-sdk';
import { type Json, query } from '../util';
import { accountHeaders, sessionIds } from './auth';
import type { YouTubeClient } from './clients';
import { locale, markExpired, usableSession, visitorData } from './session';

export type Site = 'music' | 'www';

const ORIGINS: Record<Site, string> = { music: 'https://music.youtube.com', www: 'https://www.youtube.com' };

export interface InnerTubeOptions {
  client: YouTubeClient;
  site?: Site;
  /** Sign the request as the listener, when signed in and the client supports it. */
  auth?: boolean;
  body?: Json;
  params?: Record<string, string | undefined>;
  /** Overrides the visitor id, e.g. the account's own for signed-in playback. */
  visitor?: string;
  timeoutMs?: number;
}

export async function innertube(endpoint: string, options: InnerTubeOptions): Promise<Json> {
  const { client } = options;
  const origin = ORIGINS[options.site ?? 'music'];
  const session = options.auth && client.loginSupported ? await usableSession() : undefined;
  const visitor = options.visitor ?? session?.visitorData ?? (await visitorData());
  const { hl, gl } = await locale();

  const headers: Record<string, string> = {
    'content-type': 'application/json',
    'user-agent': client.userAgent,
    'x-goog-api-format-version': '1',
    'x-youtube-client-name': client.clientId,
    'x-youtube-client-version': client.clientVersion,
    'x-origin': origin,
    referer: `${origin}/`,
    'x-goog-visitor-id': visitor,
    ...(session ? await accountHeaders(session, origin) : {}),
  };

  const context = {
    client: {
      clientName: client.clientName,
      clientVersion: client.clientVersion,
      osName: client.osName,
      osVersion: client.osVersion,
      deviceMake: client.deviceMake,
      deviceModel: client.deviceModel,
      androidSdkVersion: client.androidSdkVersion,
      originalUrl: client.originalUrl,
      platform: client.platform,
      utcOffsetMinutes: client.utcOffsetMinutes,
      gl,
      hl,
      visitorData: visitor,
      userAgent: client.sendUserAgentInContext ? client.userAgent : undefined,
    },
    user: { onBehalfOfUser: session ? sessionIds(session).delegated : undefined },
  };

  const response = await mb.http.fetch({
    url: `${origin}/youtubei/v1/${endpoint}?${query({ prettyPrint: 'false', ...options.params })}`,
    method: 'POST',
    headers,
    body: JSON.stringify({ context, ...options.body }),
    timeoutMs: options.timeoutMs,
  });

  if (response.status === 401 || (response.status === 403 && session)) {
    if (session) await markExpired();
    fail('SIGN_IN_EXPIRED', `${endpoint} answered ${response.status}`);
  }
  if (response.status === 429) fail('RATE_LIMITED', `${endpoint} is rate limited`, { retryAfterMs: 30_000 });
  if (response.status < 200 || response.status >= 300) fail('NETWORK', `${endpoint} answered ${response.status}`);
  return JSON.parse(response.body);
}
