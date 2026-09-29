// video.resolve: a video's direct adaptive formats or live manifests, walking the app's
// InnerTubeVideoStreamExtractor ladder on the main site (SABR left out: direct formats only).
// 1. VISIONOS, token- and n-free, every quality up to 4K and no ads; then, signed in, the account's
// TV client for what VISIONOS refuses (made-for-kids, age-restricted, members-only); 2. live manifest clients when the video is live; 3. MWEB/WEB with a
// BotGuard PO Token; 4. the ANDROID_VR builds GVS cuts off ~60 s in; 5. MOBILE, even with an untransformed
// `n`; 6. any demoted client, since a minute of video beats none. Clients GVS refused recently are
// skipped for 30 minutes (ClientGateTracker).
import type { MediaFormat, ResolveVideoRequest, SkipSegment, StreamFailure, VideoPlayback } from '@milkbeat/plugin-sdk';
import { fail, mb } from '@milkbeat/plugin-sdk';
import type { YouTubeClient } from '../innertube/clients';
import type { Json } from '../util';
import { resolveCaptions } from './captions';
import { skipSegments, type WatchExtras, watchExtras } from './extras';
import { type RawFormat, toMediaFormat } from './formats';
import {
  ClientGates,
  classifyDenial,
  clientOfUrl,
  LIVE_MANIFEST_CLIENTS,
  VIDEO_FAST_CLIENTS,
  VIDEO_GATED_CLIENTS,
  VIDEO_LAST_RESORT_CLIENTS,
  VIDEO_SIGNED_IN_CLIENTS,
  VIDEO_WEB_CLIENTS,
} from './ladder';
import { decipherWhilePossible, transformN, withPoToken } from './playable';
import { adaptiveFormats, adWaitMs, expiresInMs, isBotWall, isLiveNow, liveManifests, playability, playabilityReason, requestPlayer } from './player';
import { type AccountSession, usableSession } from '../innertube/session';
import { mintPoTokens, reportTokenRejected } from './potoken/session';
import { ensureSolvers, signatureTimestamp, solversReady } from './solver';
import { tokenVisitor } from './visitor';
import { upcomingPlayback, videoDetails } from './video-details';
import { filterVideoFormats, orderAudioFormats } from './video-formats';

const PER_CLIENT_TIMEOUT_MS = 6_000;
const WEB_PLAYER_TIMEOUT_MS = 10_000;
const LADDER_BUDGET_MS = 17_000;

export const gates = new ClientGates();

interface Extraction {
  response: Json;
  client: YouTubeClient;
  receivedAt: number;
  live?: { hls?: string; dash?: string };
  /** How long after [receivedAt] GVS opens the URLs (a signed-in pre-roll ad); zero for most. */
  adWaitMs?: number;
  video: Array<{ format: RawFormat; url: string }>;
  audio: Array<{ format: RawFormat; url: string }>;
}

interface Attempt {
  videoId: string;
  deadline: number;
  reasons: string[];
  answers: Json[];
  liveDetected: boolean;
  webNeedsSolvers?: boolean;
}

/** A refused stream: expired URLs need nothing, an unattested client is demoted, a refused token counts a strike. */
export function reportStreamFailure(failure: StreamFailure, nowSeconds = Math.floor(Date.now() / 1000)): void {
  if (failure.status !== 403) return;
  const client = clientOfUrl(failure.url);
  switch (classifyDenial(failure.url, nowSeconds)) {
    case 'ATTESTATION_GATED':
      gates.reportGated(client);
      break;
    case 'TOKEN_REJECTED':
      reportTokenRejected();
      gates.reportRefused(client);
      break;
    default:
      break;
  }
}

async function ask(
  attempt: Attempt,
  client: YouTubeClient,
  extra: { sts?: number; poToken?: string; visitor?: string; auth?: boolean; timeoutMs: number },
) {
  const { sts, ...rest } = extra;
  try {
    const response = await requestPlayer({ client, videoId: attempt.videoId, site: 'www', signatureTimestamp: sts, ...rest });
    attempt.answers.push(response);
    const status = playability(response);
    if (status === 'OK') return response;
    const reason = playabilityReason(response);
    attempt.reasons.push(`${client.clientName}: ${isBotWall(reason) ? 'BOT_WALL' : `status=${status}`}, reason=${reason}`);
  } catch (error) {
    attempt.reasons.push(`${client.clientName}: ${error instanceof Error ? error.message : String(error)}`);
  }
  return undefined;
}

/** Each format with its playable URL: deciphered, `n` transformed (or kept raw only when allowed), token attached. */
async function playableFormats(formats: RawFormat[], allowUntransformedN: boolean, streamingPot?: string) {
  const needsSolver = formats.some((format) => !format.url || /[?&]n=/.test(format.url));
  if (needsSolver && !solversReady()) await ensureSolvers();
  const playable: Array<{ format: RawFormat; url: string }> = [];
  for (const { format, url } of decipherWhilePossible(formats)) {
    const transformed = transformN(url) ?? (allowUntransformedN ? url : undefined);
    if (transformed) playable.push({ format, url: withPoToken(transformed, streamingPot) });
  }
  return playable;
}

function split(response: Json, client: YouTubeClient, formats: Array<{ format: RawFormat; url: string }>): Extraction {
  return {
    response,
    client,
    receivedAt: Date.now(),
    video: formats.filter(({ format }) => format.mimeType.startsWith('video/') && format.height && format.width),
    audio: formats.filter(({ format }) => format.mimeType.startsWith('audio/')),
  };
}

async function tryDirect(attempt: Attempt, clients: YouTubeClient[], allowUntransformedN = false): Promise<Extraction | undefined> {
  const sts = clients.some((client) => client.useSignatureTimestamp) ? await signatureTimestamp().catch(() => undefined) : undefined;
  for (const client of clients) {
    if (Date.now() > attempt.deadline) break;
    const response = await ask(attempt, client, { sts: client.useSignatureTimestamp ? sts : undefined, timeoutMs: PER_CLIENT_TIMEOUT_MS });
    if (!response) continue;
    if (isLiveNow(response)) {
      const live = liveManifests(response);
      if (live) return { response, client, receivedAt: Date.now(), live, video: [], audio: [] };
      if (response.videoDetails?.isLive === true) {
        attempt.liveDetected = true;
        attempt.reasons.push(`${client.clientName}: live but no manifest`);
        continue;
      }
    }
    const formats = adaptiveFormats(response);
    if (!formats.length) {
      attempt.reasons.push(`${client.clientName}: no adaptive formats`);
      continue;
    }
    const extraction = split(response, client, await playableFormats(formats, allowUntransformedN));
    if (extraction.video.length && extraction.audio.length) return extraction;
    attempt.reasons.push(`${client.clientName}: ${formats.length} formats, ${extraction.video.length} video / ${extraction.audio.length} audio playable`);
  }
  return undefined;
}

async function tryLive(attempt: Attempt): Promise<Extraction | undefined> {
  for (const client of LIVE_MANIFEST_CLIENTS) {
    const response = await ask(attempt, client, { timeoutMs: PER_CLIENT_TIMEOUT_MS });
    const live = response && liveManifests(response);
    if (live) return { response, client, receivedAt: Date.now(), live, video: [], audio: [] };
  }
  return undefined;
}

/** The attested web path: a PO Token bound to the video for /player, the visitor-bound one on every URL. */
async function tryWeb(attempt: Attempt, clients: YouTubeClient[], prepare = false): Promise<Extraction | undefined> {
  if (!clients.length) return undefined;
  const visitor = await tokenVisitor();
  const tokens = await mintPoTokens(attempt.videoId, visitor);
  if (!tokens) {
    attempt.reasons.push('web: PO Token unavailable');
    return undefined;
  }
  const sts = await signatureTimestamp().catch(() => undefined);
  for (const client of clients) {
    if (Date.now() > attempt.deadline && !prepare) break;
    const response = await ask(attempt, client, { sts, poToken: tokens.player, visitor, timeoutMs: WEB_PLAYER_TIMEOUT_MS });
    if (!response) continue;
    if (prepare) await ensureSolvers(true);
    const extraction = split(response, client, await playableFormats(adaptiveFormats(response), false, tokens.streaming));
    if (extraction.video.length && extraction.audio.length) return extraction;
    if (!solversReady()) attempt.webNeedsSolvers = true;
    attempt.reasons.push(`${client.clientName}: no format survived decipher/n-transform${solversReady() ? '' : ' (solvers not prepared)'}`);
  }
  return undefined;
}

/**
 * The account's clients, asked as the account; their URLs need the solvers but no PO Token. Without
 * Premium they open only after the pre-roll ad's skip point, which the answer reports so the host
 * waits only when playback would otherwise start sooner.
 */
async function trySignedIn(attempt: Attempt, session: AccountSession): Promise<Extraction | undefined> {
  const clients = gates.ungated(VIDEO_SIGNED_IN_CLIENTS);
  if (!clients.length) return undefined;
  if (!solversReady()) await ensureSolvers(true);
  const sts = await signatureTimestamp().catch(() => undefined);
  const visitor = session.visitorData ?? (await tokenVisitor());
  for (const client of clients) {
    if (Date.now() > attempt.deadline) break;
    const response = await ask(attempt, client, { sts, visitor, auth: true, timeoutMs: WEB_PLAYER_TIMEOUT_MS });
    if (!response) continue;
    if (isLiveNow(response)) {
      const live = liveManifests(response);
      if (live) return { response, client, receivedAt: Date.now(), live, video: [], audio: [] };
    }
    const extraction = split(response, client, await playableFormats(adaptiveFormats(response), false));
    if (!extraction.video.length || !extraction.audio.length) {
      attempt.reasons.push(`${client.clientName} (signed in): no format survived decipher/n-transform`);
      continue;
    }
    return { ...extraction, adWaitMs: adWaitMs(response) };
  }
  return undefined;
}

async function extract(attempt: Attempt): Promise<Extraction | undefined> {
  const fast = await tryDirect(attempt, gates.ungated(VIDEO_FAST_CLIENTS));
  if (fast) return fast;
  const session = await usableSession();
  if (session) {
    const own = await trySignedIn(attempt, session);
    if (own) return own;
  }
  if (attempt.liveDetected) {
    const live = await tryLive(attempt);
    if (live) return live;
  }
  const web = await tryWeb(attempt, gates.ungated(VIDEO_WEB_CLIENTS));
  if (web) return web;
  const gated = await tryDirect(attempt, gates.ungated(VIDEO_GATED_CLIENTS));
  if (gated) return gated;
  const lastResort = await tryDirect(attempt, gates.ungated(VIDEO_LAST_RESORT_CLIENTS), true);
  if (lastResort) return lastResort;
  const demoted = gates.gated([...VIDEO_FAST_CLIENTS, ...VIDEO_GATED_CLIENTS, ...VIDEO_LAST_RESORT_CLIENTS]);
  if (demoted.length) {
    const retried = await tryDirect(attempt, demoted, true);
    if (retried) return retried;
  }
  // Only ciphered web formats were left and the solvers were never prepared: prepare them now.
  if (attempt.webNeedsSolvers) return tryWeb(attempt, gates.ungated(VIDEO_WEB_CLIENTS), true);
  return undefined;
}

const sideLookups = new Map<string, { extras: Promise<WatchExtras>; segments: Promise<SkipSegment[]> }>();

/** The watch page and SponsorBlock, fetched once per video: a re-resolve for fresh URLs reuses them. */
function sideLookupsFor(videoId: string) {
  let lookups = sideLookups.get(videoId);
  if (!lookups) {
    lookups = { extras: watchExtras(videoId), segments: skipSegments(videoId) };
    sideLookups.set(videoId, lookups);
    if (sideLookups.size > 8) sideLookups.delete(sideLookups.keys().next().value as string);
  }
  return lookups;
}

export async function resolveVideo(request: ResolveVideoRequest): Promise<VideoPlayback> {
  const videoId = request.entity.providerId;
  if (request.failure) reportStreamFailure(request.failure);
  const { extras, segments } = sideLookupsFor(videoId);
  const attempt: Attempt = { videoId, deadline: Date.now() + LADDER_BUDGET_MS, reasons: [], answers: [], liveDetected: false };
  const extraction = await extract(attempt);

  if (!extraction) {
    const upcoming = upcomingPlayback(request.entity, attempt.answers, await extras);
    if (upcoming) return upcoming;
    const reason = attempt.answers.map(playabilityReason).find((text) => !!text);
    fail('UNAVAILABLE', `No client resolved ${videoId}: ${attempt.reasons.join(' | ')}`, { userMessage: reason });
  }

  const { response, client } = extraction;
  const tallest = Math.max(0, ...extraction.video.map(({ format }) => format.height ?? 0));
  await mb.log.write({ level: 'INFO', message: `${videoId} via ${client.clientName} ${client.clientVersion}: ${extraction.video.length} video formats up to ${tallest}p` });
  const headers = { 'User-Agent': client.userAgent };
  const formats: MediaFormat[] = [
    ...filterVideoFormats(extraction.video, request.maxHeight, request.codecs ?? []),
    ...orderAudioFormats(extraction.audio, request.language),
  ].map(({ format, url }) => toMediaFormat(format, url));
  const [watch, skips] = await Promise.all([extras, segments]);
  return {
    kind: extraction.live ? 'LIVE' : 'VOD',
    details: videoDetails(request.entity, response, watch),
    formats,
    hlsUrl: extraction.live?.hls,
    dashUrl: extraction.live?.dash,
    captions: resolveCaptions(response, request.captionLanguage),
    chapters: watch.chapters,
    skipSegments: extraction.live ? [] : skips,
    headers,
    expiresInMs: expiresInMs(response, extraction.receivedAt, Date.now()),
    availableInMs: extraction.adWaitMs ? Math.max(0, extraction.adWaitMs - (Date.now() - extraction.receivedAt)) || undefined : undefined,
    dvr: !!extraction.live && response?.videoDetails?.isLiveDvrEnabled === true,
  };
}
