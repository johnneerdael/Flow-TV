// audio.resolve: a track's sound (and its music video's picture) from one player response, walking
// the app's client ladder (MusicPlayerUtils.fetchPlaybackData): fast direct clients, WEB_REMIX
// direct, then the slow rescue through the signature cipher, and last the signed-in account's own
// response. A failed stream escalates the track past the fast clients for two minutes.
import type { AudioQuality, AudioStream, MediaFormat, ResolveAudioRequest } from '@milkbeat/plugin-sdk';
import { fail, mb } from '@milkbeat/plugin-sdk';
import type { YouTubeClient } from '../innertube/clients';
import type { Json } from '../util';
import { codecsOf, isAudio, type RawFormat, renditionId, selectAudioFormat, selectMusicVideoFormat, toMediaFormat, toNumber } from './formats';
import { Escalations, MUSIC_MAIN_CLIENT, musicFastLadder, musicRescueLadder, needsValidation } from './ladder';
import { cipherResolvedUrl, hasCipher, transformN, withPoToken } from './playable';
import { adaptiveFormats, expiresInMs, playability, requestPlayer } from './player';
import { type PoTokens, mintPoTokens } from './potoken/session';
import { ensureSolvers, signatureTimestamp, solversReady } from './solver';
import { encodeTrackingToken, signedInPlayer } from './tracking';
import { tokenVisitor } from './visitor';

const PER_CLIENT_TIMEOUT_MS = 6_000;
const VALIDATION_TIMEOUT_MS = 2_000;
const LADDER_BUDGET_MS = 16_000;
const MAX_CACHE_TTL_MS = 600_000;
const LOUDNESS_TARGET_LKFS = -14;

interface Choice {
  quality: AudioQuality;
  language: string | null | undefined;
}

/** A resolved track: the response its URLs came from, the client that asked, and the chosen sound. */
interface Playback {
  response: Json;
  audioConfig: Json;
  client: YouTubeClient;
  format: RawFormat;
  url: string;
  streamingPot?: string;
  receivedAt: number;
}

const escalations = new Escalations();
const cache = new Map<string, { playback: Playback; until: number }>();
const inFlight = new Map<string, Promise<Playback>>();

/** The YouTube video behind a track: its id in this plugin's id spaces. */
export function trackVideoId(request: ResolveAudioRequest): string | undefined {
  const ids = request.track.ids ?? {};
  return ids.ytm || ids.yt || undefined;
}

async function validates(url: string, client: YouTubeClient): Promise<boolean> {
  try {
    const response = await mb.http.fetch({ url, method: 'HEAD', headers: { 'user-agent': client.userAgent }, timeoutMs: VALIDATION_TIMEOUT_MS });
    return response.status >= 200 && response.status < 300;
  } catch {
    return false;
  }
}

interface ExtractOptions {
  validate: boolean;
  requireDirectUrl: boolean;
  allowCipher: boolean;
}

async function tryExtract(response: Json, client: YouTubeClient, choice: Choice, options: ExtractOptions) {
  if (playability(response) !== 'OK') return undefined;
  const format = selectAudioFormat(adaptiveFormats(response), { ...choice, requireDirectUrl: options.requireDirectUrl });
  if (!format) return undefined;
  if (!format.url && options.allowCipher && hasCipher(format)) await ensureSolvers();
  const url = format.url ?? (options.allowCipher ? cipherResolvedUrl(format) : undefined);
  if (!url) return undefined;
  if (options.validate && needsValidation(client) && !(await validates(url, client))) return undefined;
  return { format, url };
}

/** The `n` challenge solved (the raw URL when it cannot be, as the app did), then the streaming token. */
async function playableUrl(url: string, streamingPot: string | undefined): Promise<string> {
  let playable = url;
  if (/[?&]n=/.test(url)) {
    if (!solversReady()) await ensureSolvers();
    playable = transformN(url) ?? url;
  }
  return withPoToken(playable, streamingPot);
}

async function fetchPlayback(videoId: string, choice: Choice): Promise<Playback> {
  const deadline = Date.now() + LADDER_BUDGET_MS;
  let sts: Promise<number | undefined> | undefined;
  let tokens: Promise<PoTokens | undefined> | undefined;
  const stsFor = (client: YouTubeClient) =>
    client.useSignatureTimestamp ? (sts ??= signatureTimestamp().catch(() => undefined)) : Promise.resolve(undefined);
  const tokensFor = (client: YouTubeClient) =>
    client.useWebPoTokens ? (tokens ??= tokenVisitor().then((visitor) => mintPoTokens(videoId, visitor))) : Promise.resolve(undefined);

  // The account's own request only records the listen; it never holds up the stream.
  const signedIn = signedInPlayer(videoId);

  const ask = async (client: YouTubeClient): Promise<Json | undefined> => {
    try {
      const [signature, pot] = await Promise.all([stsFor(client), tokensFor(client)]);
      const visitor = pot ? await tokenVisitor() : undefined;
      return await requestPlayer({ client, videoId, site: 'music', signatureTimestamp: signature, poToken: pot?.player, visitor, timeoutMs: PER_CLIENT_TIMEOUT_MS });
    } catch (error) {
      await mb.log.write({ level: 'DEBUG', message: `${client.clientName} failed for ${videoId}: ${error instanceof Error ? error.message : String(error)}` });
      return undefined;
    }
  };

  let found: { response: Json; client: YouTubeClient; format: RawFormat; url: string } | undefined;
  if (!escalations.isEscalated(videoId)) {
    for (const client of musicFastLadder()) {
      if (Date.now() > deadline) break;
      const response = await ask(client);
      const extracted = response && (await tryExtract(response, client, choice, { validate: true, requireDirectUrl: true, allowCipher: false }));
      if (extracted) {
        found = { response, client, ...extracted };
        break;
      }
    }
  }

  let main: Json | undefined;
  if (!found) {
    main = await ask(MUSIC_MAIN_CLIENT);
    const extracted = main && (await tryExtract(main, MUSIC_MAIN_CLIENT, choice, { validate: false, requireDirectUrl: true, allowCipher: false }));
    if (extracted) found = { response: main, client: MUSIC_MAIN_CLIENT, ...extracted };
  }

  if (!found && main) {
    const extracted = await tryExtract(main, MUSIC_MAIN_CLIENT, choice, { validate: false, requireDirectUrl: false, allowCipher: true });
    if (extracted) found = { response: main, client: MUSIC_MAIN_CLIENT, ...extracted };
  }

  if (!found) {
    const rescue = musicRescueLadder();
    for (const [index, client] of rescue.entries()) {
      if (Date.now() > deadline) break;
      const response = await ask(client);
      const validate = index !== rescue.length - 1;
      const extracted = response && (await tryExtract(response, client, choice, { validate, requireDirectUrl: false, allowCipher: true }));
      if (extracted) {
        found = { response, client, ...extracted };
        break;
      }
    }
  }

  if (!found) {
    const account = await signedIn;
    const extracted = account && (await tryExtract(account, MUSIC_MAIN_CLIENT, choice, { validate: false, requireDirectUrl: false, allowCipher: true }));
    if (extracted) found = { response: account, client: MUSIC_MAIN_CLIENT, ...extracted };
  }

  // Nothing but ciphered formats and no prepared solvers: prepare them now, the one inline preprocess.
  const ciphered = main && playability(main) === 'OK' && !solversReady();
  if (!found && ciphered && (await ensureSolvers(true))) {
    const extracted = await tryExtract(main, MUSIC_MAIN_CLIENT, choice, { validate: false, requireDirectUrl: false, allowCipher: true });
    if (extracted) found = { response: main, client: MUSIC_MAIN_CLIENT, ...extracted };
  }

  if (!found) fail('UNAVAILABLE', `No client resolved a stream for ${videoId}`);

  const streamingPot = found.client.useWebPoTokens ? (await tokensFor(found.client))?.streaming : undefined;
  return {
    response: found.response,
    audioConfig: main?.playerConfig?.audioConfig ?? found.response?.playerConfig?.audioConfig,
    client: found.client,
    format: found.format,
    url: await playableUrl(found.url, streamingPot),
    streamingPot,
    receivedAt: Date.now(),
  };
}

async function playbackFor(videoId: string, choice: Choice, fresh: boolean): Promise<Playback> {
  const key = `${videoId}|${choice.quality}|${choice.language ?? ''}`;
  if (fresh) for (const cached of [...cache.keys()]) if (cached.startsWith(`${videoId}|`)) cache.delete(cached);
  const cached = cache.get(key);
  if (cached && Date.now() < cached.until) return cached.playback;
  const running = inFlight.get(key);
  if (running) return running;
  const promise = fetchPlayback(videoId, choice)
    .then((playback) => {
      const ttl = Math.max(30_000, Math.min(expiresInMs(playback.response, playback.receivedAt, playback.receivedAt) - 60_000, MAX_CACHE_TTL_MS));
      cache.set(key, { playback, until: Date.now() + ttl });
      if (cache.size > 20) cache.delete(cache.keys().next().value as string);
      return playback;
    })
    .finally(() => inFlight.delete(key));
  inFlight.set(key, promise);
  return promise;
}

/** Loudness relative to YouTube's reference: perceptual loudness over the target, else the stated offset. */
export function loudnessDb(audioConfig: Json, format: RawFormat): number | undefined {
  if (typeof audioConfig?.perceptualLoudnessDb === 'number') {
    return audioConfig.perceptualLoudnessDb - (audioConfig.loudnessTargetLkfs ?? LOUDNESS_TARGET_LKFS);
  }
  if (typeof audioConfig?.loudnessDb === 'number') return audioConfig.loudnessDb;
  return typeof format.loudnessDb === 'number' ? format.loudnessDb : undefined;
}

async function musicVideo(playback: Playback, request: ResolveAudioRequest, headers: Record<string, string>): Promise<MediaFormat | undefined> {
  const format = selectMusicVideoFormat(adaptiveFormats(playback.response), request.maxVideoHeight, request.videoCodecs ?? []);
  if (!format) return undefined;
  if (hasCipher(format)) await ensureSolvers();
  const url = cipherResolvedUrl(format);
  return url ? toMediaFormat(format, await playableUrl(url, playback.streamingPot), headers) : undefined;
}

export async function resolveAudio(request: ResolveAudioRequest): Promise<AudioStream> {
  const videoId = trackVideoId(request);
  if (!videoId) fail('NOT_FOUND', `${request.track.title} has no YouTube id`);
  if (request.failure) escalations.mark(videoId);
  const choice: Choice = { quality: request.quality ?? 'AUTO', language: request.language };
  const playback = await playbackFor(videoId, choice, !!request.failure);
  const { format } = playback;
  if (!isAudio(format)) fail('INTERNAL', `Chose a non-audio format for ${videoId}`);
  const headers = { 'User-Agent': playback.client.userAgent };
  return {
    url: playback.url,
    cacheKey: videoId,
    renditionId: renditionId(format),
    mimeType: format.mimeType.split(';')[0].trim(),
    codecs: codecsOf(format.mimeType) || undefined,
    bitrate: format.bitrate,
    contentLength: toNumber(format.contentLength),
    headers,
    expiresInMs: expiresInMs(playback.response, playback.receivedAt, Date.now()),
    loudnessDb: loudnessDb(playback.audioConfig, format),
    trackingToken: encodeTrackingToken(videoId),
    video: request.video ? await musicVideo(playback, request, headers) : undefined,
  };
}
