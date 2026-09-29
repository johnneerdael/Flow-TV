// A video's details from its player response and watch page, and the premiere countdown when nothing
// is playable yet (the app's UpcomingPremiereProbe). Pure.
import type { EntityRef, VideoDetails, VideoPlayback } from '@milkbeat/plugin-sdk';
import { artwork, type Json } from '../util';
import type { WatchExtras } from './extras';
import { adaptiveFormats } from './player';

export function videoDetails(entity: EntityRef, response: Json, extras: Partial<WatchExtras> = {}): VideoDetails {
  const details = response?.videoDetails ?? {};
  const length = Number(details.lengthSeconds);
  return {
    entity,
    title: details.title ?? '',
    channelName: details.author,
    channel: details.channelId ? { kind: 'CHANNEL', providerId: details.channelId } : undefined,
    channelAvatar: extras.channelAvatar,
    durationSeconds: Number.isFinite(length) && length > 0 ? length : undefined,
    description: details.shortDescription,
    viewsLabel: extras.viewsLabel,
    publishedLabel: extras.publishedLabel,
    artwork: artwork(details.thumbnail),
    keywords: Array.isArray(details.keywords) ? details.keywords : [],
  };
}

/** A premiere or scheduled stream that has not started: offline, no manifest and no formats. */
export function looksUpcoming(response: Json): boolean {
  const status = response?.playabilityStatus;
  if (!status) return false;
  const streaming = response?.streamingData;
  const hasFormats = (streaming?.formats?.length ?? 0) > 0 || adaptiveFormats(response).length > 0;
  if (streaming?.hlsManifestUrl || hasFormats) return false;
  const reason = String(status.reason ?? '').toLowerCase();
  return (
    String(status.status ?? '').toUpperCase() === 'LIVE_STREAM_OFFLINE' ||
    !!status.liveStreamability ||
    reason.includes('premiere') ||
    reason.includes('will begin') ||
    reason.includes('scheduled')
  );
}

/** Milliseconds until the scheduled start; undefined once it is in the past, which is no countdown. */
export function startsInMs(response: Json, nowMs: number): number | undefined {
  const scheduled = Number(
    response?.playabilityStatus?.liveStreamability?.liveStreamabilityRenderer?.offlineSlate?.liveStreamOfflineSlateRenderer?.scheduledStartTime,
  );
  if (!Number.isFinite(scheduled) || scheduled <= 0) return undefined;
  const remaining = scheduled * 1000 - nowMs;
  return remaining > 0 ? remaining : undefined;
}

export function upcomingPlayback(entity: EntityRef, answers: Json[], extras: Partial<WatchExtras>, nowMs = Date.now()): VideoPlayback | undefined {
  const response = answers.find(looksUpcoming);
  if (!response) return undefined;
  return { kind: 'UPCOMING', details: videoDetails(entity, response, extras), startsInMs: startsInMs(response, nowMs) };
}
