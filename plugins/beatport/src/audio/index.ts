// Audio: a track's full-length stream, as Beatport's web player plays it (128k AAC in AES-128 HLS on
// needledrop, signed in its path until the epoch second that path starts with), and the play reported
// back as Beatport's apps report it, which is what pays the track's artists.
import type { AudioStream, PluginDefinition, ReportPlaybackRequest, ResolveAudioRequest } from '@milkbeat/plugin-sdk';
import { fail, mb } from '@milkbeat/plugin-sdk';
import { get, post } from '../api/client';

const QUALITY = '128k.aac.m3u8';
const DEVICE_KEY = 'device';
/** A stream this much shorter than its track is Beatport's preview clip, which only an unsubscribed account gets. */
const PREVIEW_SLACK_MS = 5_000;

interface StreamAnswer {
  stream_url?: string;
  sample_start_ms?: number;
  sample_end_ms?: number;
}

function trackId(ids: Record<string, string> | undefined, providerId: string): string {
  const id = ids?.beatport ?? providerId;
  if (!/^\d+$/.test(id)) fail('NOT_FOUND', `${id} is not a Beatport track`);
  return id;
}

/** How long the signed stream URL still opens, from the expiry second that leads its path. */
function expiresInMs(url: string): number | undefined {
  const match = /^https:\/\/[^/]+\/(\d{10})_/.exec(url);
  return match ? Number(match[1]) * 1000 - Date.now() : undefined;
}

async function resolve(request: ResolveAudioRequest): Promise<AudioStream> {
  const id = trackId(request.track.ids, request.track.ref.providerId);
  const lengthMs = request.track.durationMs ?? undefined;
  const answer = await get<StreamAnswer>(`catalog/tracks/${id}/stream/`, lengthMs ? { start: 0, end: lengthMs } : {});
  const url = answer.stream_url;
  if (!url) fail('UNAVAILABLE', `Beatport has no stream for track ${id}`);
  const streamedMs = (answer.sample_end_ms ?? 0) - (answer.sample_start_ms ?? 0);
  if (lengthMs && streamedMs > 0 && streamedMs < lengthMs - PREVIEW_SLACK_MS) {
    fail('UNAVAILABLE', `Beatport streams only a preview of track ${id} to this account`, {
      userMessage: 'Playing full tracks needs a Beatport streaming subscription',
    });
  }
  return {
    url,
    cacheKey: `beatport:${id}:${QUALITY}`,
    renditionId: QUALITY,
    mimeType: 'application/x-mpegURL',
    codecs: 'mp4a.40.2',
    bitrate: 128_000,
    expiresInMs: expiresInMs(url),
  };
}

/** A random id kept for this install, as Beatport's apps send one with every play. */
async function deviceId(): Promise<string> {
  const stored = (await mb.storage.get({ key: DEVICE_KEY })).value;
  if (stored) return stored;
  const id = Array.from({ length: 16 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
  await mb.storage.set({ key: DEVICE_KEY, value: id });
  return id;
}

async function reportPlayback(request: ReportPlaybackRequest): Promise<void> {
  const id = trackId(undefined, request.entity.providerId);
  const seconds = Math.floor(request.playedMs / 1000);
  if (seconds < 1) return;
  await post('events/play/', {
    device_id: await deviceId(),
    heatmap: [],
    item_id: Number(id),
    offline: false,
    play_time_seconds: seconds,
    play_timestamp: Math.floor((Date.now() - request.playedMs) / 1000),
    stream_quality: QUALITY,
  });
}

export const audio: NonNullable<PluginDefinition['audio']> = { resolve, reportPlayback };
