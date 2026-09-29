// The InnerTube /player request for playback, and what its answer says. Extraction always asks in
// en/US so the response is the same everywhere, as the app's YouTubeLocale.EXTRACTION did.
import type { YouTubeClient } from '../innertube/clients';
import { innertube, type Site } from '../innertube/request';
import { usableSession, visitorData } from '../innertube/session';
import type { Json } from '../util';
import type { RawFormat } from './formats';

export interface PlayerCall {
  client: YouTubeClient;
  videoId: string;
  site: Site;
  /** Sent when given; callers pass it only to clients that use one. */
  signatureTimestamp?: number;
  poToken?: string;
  /** Sign the request as the listener (the music path's parallel account request). */
  auth?: boolean;
  /** The visitor the PO Token was minted for; the request must carry the same one. */
  visitor?: string;
  timeoutMs?: number;
}

const EXTRACTION_LOCALE = { hl: 'en', gl: 'US' };

/** The full web player's playback context, as InnerTube.playerWeb sent it for WEB and MWEB. */
function playbackContext(call: PlayerCall): Json {
  const { client, signatureTimestamp } = call;
  if (signatureTimestamp === undefined) return undefined;
  const web = client.clientName === 'WEB' || client.clientName === 'MWEB';
  return {
    contentPlaybackContext: web
      ? {
          signatureTimestamp,
          referer: `https://www.youtube.com/watch?v=${call.videoId}`,
          vis: 0,
          splay: false,
          lactMilliseconds: '-1',
          html5Preference: 'HTML5_PREF_WANTS',
        }
      : { signatureTimestamp },
  };
}

export async function requestPlayer(call: PlayerCall): Promise<Json> {
  const { client } = call;
  const session = call.auth && client.loginSupported ? await usableSession() : undefined;
  const visitor = call.visitor ?? session?.visitorData ?? (await visitorData());
  const context: Json = {
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
      ...EXTRACTION_LOCALE,
      visitorData: visitor,
      userAgent: client.sendUserAgentInContext ? client.userAgent : undefined,
    },
    user: { onBehalfOfUser: session?.dataSyncId },
    thirdParty: client.isEmbedded ? { embedUrl: `https://www.youtube.com/watch?v=${call.videoId}` } : undefined,
  };
  return innertube('player', {
    client,
    site: call.site,
    auth: !!session,
    visitor,
    timeoutMs: call.timeoutMs,
    body: {
      context,
      videoId: call.videoId,
      playbackContext: playbackContext(call),
      serviceIntegrityDimensions: call.poToken ? { poToken: call.poToken } : undefined,
      contentCheckOk: true,
      racyCheckOk: true,
    },
  });
}

export const playability = (response: Json): string | undefined => response?.playabilityStatus?.status;
export const playabilityReason = (response: Json): string | undefined =>
  response?.playabilityStatus?.reason ?? response?.playabilityStatus?.messages?.[0];

export const adaptiveFormats = (response: Json): RawFormat[] =>
  Array.isArray(response?.streamingData?.adaptiveFormats) ? response.streamingData.adaptiveFormats : [];

/** YouTube's "confirm you're not a bot" wall, which no other client of the same family gets past. */
export function isBotWall(reason: string | undefined): boolean {
  if (!reason) return false;
  const lower = reason.toLowerCase();
  return lower.includes('sign in to confirm') || lower.includes('confirm you') || lower.includes('not a bot') || lower.includes('inicia sesión');
}

/**
 * Live now: flagged live or post-live DVR, or live streamability; an HLS manifest alone only counts
 * when there is no adaptive ladder (Apple clients return one for ordinary videos too).
 */
export function isLiveNow(response: Json): boolean {
  const details = response?.videoDetails;
  if (details?.isLive === true || details?.isPostLiveDvr === true) return true;
  if (response?.playabilityStatus?.liveStreamability) return true;
  return !!response?.streamingData?.hlsManifestUrl && adaptiveFormats(response).length === 0;
}

export function liveManifests(response: Json): { hls?: string; dash?: string } | undefined {
  const hls: string | undefined = response?.streamingData?.hlsManifestUrl || undefined;
  const dash: string | undefined = response?.streamingData?.dashManifestUrl || undefined;
  return hls || dash ? { hls, dash } : undefined;
}

/** How long the response's URLs stay valid, relative to now: TV clocks are often wrong. */
export function expiresInMs(response: Json, receivedAt: number, now: number): number {
  const seconds = Number(response?.streamingData?.expiresInSeconds);
  return Math.max(0, (Number.isFinite(seconds) && seconds > 0 ? seconds : 21_600) * 1000 - (now - receivedAt));
}
