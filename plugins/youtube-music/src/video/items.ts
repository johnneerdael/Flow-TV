// Entries as the host's page items and queue tracks: videos wide, channels round, playlists wide
// (their artwork is a video frame). Every video carries its track so the host can queue it directly.
import type { CollectionHeader, EntityRef, MetadataItem, PageBlockCollection, TrackDescriptor } from '@milkbeat/plugin-sdk';
import type { Entry, VideoEntry } from './entries';

export const videoRef = (id: string): EntityRef => ({ kind: 'VIDEO', providerId: id });
export const channelRef = (id: string): EntityRef => ({ kind: 'CHANNEL', providerId: id });
export const playlistRef = (id: string): EntityRef => ({ kind: 'PLAYLIST', providerId: id });

export function toTrack(video: VideoEntry): TrackDescriptor {
  return {
    ref: videoRef(video.id),
    title: video.title,
    artists: video.channel?.name
      ? [{ name: video.channel.name, entity: video.channel.id ? channelRef(video.channel.id) : undefined }]
      : [],
    durationMs: video.durationSeconds !== undefined ? video.durationSeconds * 1000 : undefined,
    artwork: video.artwork,
    hasVideo: true,
    ids: { yt: video.id },
  };
}

/** [scope] keeps the id unique on the page when the same entity appears in two blocks. */
export function toItem(entry: Entry, scope = ''): MetadataItem {
  const id = `${scope}${entry.kind.toLowerCase()}:${entry.id}`;
  switch (entry.kind) {
    case 'VIDEO': {
      const track = toTrack(entry);
      return {
        id,
        entity: track.ref,
        title: entry.title,
        subtitle: entry.channel?.name,
        artwork: entry.artwork,
        view: 'LANDSCAPE_CARD',
        artists: track.artists,
        durationSeconds: entry.durationSeconds,
        details: entry.details,
        live: entry.live,
        upcoming: entry.upcoming,
        track,
      };
    }
    case 'CHANNEL':
      return {
        id,
        entity: channelRef(entry.id),
        title: entry.title,
        subtitle: entry.details[0],
        artwork: entry.artwork,
        view: 'ARTIST_PORTRAIT',
        details: entry.details.slice(1),
      };
    case 'PLAYLIST': {
      const subtitle = entry.channel?.name ?? entry.details[0];
      return {
        id,
        entity: playlistRef(entry.id),
        title: entry.title,
        subtitle,
        artwork: entry.artwork,
        view: 'LANDSCAPE_CARD',
        artists: entry.channel?.name
          ? [{ name: entry.channel.name, entity: entry.channel.id ? channelRef(entry.channel.id) : undefined }]
          : [],
        details: entry.details.filter((line) => line !== subtitle),
      };
    }
  }
}

/** A run of items; the host appends a later page's items to the block with the same id. */
export function collection(
  id: string,
  items: MetadataItem[],
  header: CollectionHeader | null = null,
  layout: PageBlockCollection['layout'] = 'HORIZONTAL_SHELF',
): PageBlockCollection {
  return { type: 'collection', id, header, layout, defaultItemView: layout === 'TRACK_TABLE' ? 'TRACK_ROW' : 'LANDSCAPE_CARD', items };
}

export const isVideo = (entry: Entry): entry is VideoEntry => entry.kind === 'VIDEO';
