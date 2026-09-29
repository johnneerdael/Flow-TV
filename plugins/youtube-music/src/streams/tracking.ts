// Listens in the signed-in account's history, as YouTube Music itself records them: the account's own
// WEB_REMIX /player request (run beside the stream lookup, never delaying it) returns a
// `videostatsPlaybackUrl`, and pinging that on the account's session adds the play. Ported from
// SignedInPlayback, AccountFeedClient.recordPlay and InnerTube.registerPlayback.
import type { ReportPlaybackRequest } from '@milkbeat/plugin-sdk';
import { fail, mb } from '@milkbeat/plugin-sdk';
import { WEB_REMIX } from '../innertube/clients';
import { usableSession, visitorData } from '../innertube/session';
import { type Json, parseCookies } from '../util';
import { randomCpn } from './bytes';
import { MUSIC_MAIN_CLIENT } from './ladder';
import { playability, requestPlayer } from './player';
import { mintPoTokens } from './potoken/session';
import { signatureTimestamp } from './solver';
import { withQueryParam } from './urls';

const SIGNED_IN_TIMEOUT_MS = 10_000;
const REMEMBERED = 200;

const trackingUrls = new Map<string, string>();

function remember(videoId: string, url: string): void {
  trackingUrls.delete(videoId);
  trackingUrls.set(videoId, url);
  if (trackingUrls.size > REMEMBERED) trackingUrls.delete(trackingUrls.keys().next().value as string);
}

/** What the host hands back with the listen: the video, and the tracking URL when it was ready. */
export interface TrackingToken {
  v: string;
  u?: string;
}

export function encodeTrackingToken(videoId: string): string {
  const token: TrackingToken = { v: videoId, u: trackingUrls.get(videoId) };
  return JSON.stringify(token);
}

export function decodeTrackingToken(token: string | null | undefined): TrackingToken | undefined {
  if (!token) return undefined;
  try {
    const parsed = JSON.parse(token) as TrackingToken;
    return typeof parsed?.v === 'string' ? parsed : undefined;
  } catch {
    return undefined;
  }
}

const trackingUrlOf = (response: Json): string | undefined => response?.playbackTracking?.videostatsPlaybackUrl?.baseUrl || undefined;

/**
 * The account's player request for [videoId]; resolves to its response when playable, and remembers
 * the tracking URL. Undefined when signed out.
 */
export async function signedInPlayer(videoId: string): Promise<Json | undefined> {
  const session = await usableSession();
  if (!session) return undefined;
  try {
    const [sts, tokens] = await Promise.all([
      signatureTimestamp().catch(() => undefined),
      mintPoTokens(videoId, session.visitorData ?? (await visitorData())),
    ]);
    const response = await requestPlayer({
      client: MUSIC_MAIN_CLIENT,
      videoId,
      site: 'music',
      auth: true,
      signatureTimestamp: sts,
      poToken: tokens?.player,
      timeoutMs: SIGNED_IN_TIMEOUT_MS,
    });
    if (playability(response) !== 'OK') return undefined;
    const url = trackingUrlOf(response);
    if (url) remember(videoId, url);
    return response;
  } catch (error) {
    await mb.log.write({ level: 'WARN', message: `Signed-in player failed: ${error instanceof Error ? error.message : String(error)}` });
    return undefined;
  }
}

async function registerPlayback(trackingUrl: string, cookie: string, visitor: string): Promise<void> {
  const origin = 'https://music.youtube.com';
  let url = trackingUrl.replace('https://s.youtube.com', origin);
  url = withQueryParam(withQueryParam(withQueryParam(url, 'ver', '2'), 'c', WEB_REMIX.clientName), 'cpn', randomCpn());
  const now = Math.floor(Date.now() / 1000);
  const hash = await mb.crypto.hash({ algorithm: 'SHA1', text: `${now} ${parseCookies(cookie).SAPISID} ${origin}` });
  const response = await mb.http.fetch({
    url,
    headers: {
      'user-agent': WEB_REMIX.userAgent,
      'x-goog-api-format-version': '1',
      'x-youtube-client-name': WEB_REMIX.clientId,
      'x-youtube-client-version': WEB_REMIX.clientVersion,
      'x-origin': origin,
      referer: `${origin}/`,
      'x-goog-visitor-id': visitor,
      cookie,
      authorization: `SAPISIDHASH ${now}_${hash.hex}`,
    },
  });
  if (response.status < 200 || response.status >= 300) fail('NETWORK', `Playback tracking answered ${response.status}`);
}

/** audio.reportPlayback: adds the listen to the account's history; nothing to do signed out. */
export async function reportPlayback(request: ReportPlaybackRequest): Promise<void> {
  const session = await usableSession();
  if (!session) return;
  const token = decodeTrackingToken(request.trackingToken);
  const videoId = token?.v ?? request.entity.providerId;
  const url = token?.u ?? trackingUrls.get(videoId) ?? trackingUrlOf(await signedInPlayer(videoId));
  if (!url) fail('UNAVAILABLE', `No playback tracking for ${videoId}`);
  await registerPlayback(url, session.cookie, session.visitorData ?? (await visitorData()));
}
