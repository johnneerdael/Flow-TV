import type { ArtistCredit, Artwork, EntityRef, MetadataItem, TrackDescriptor } from '@milkbeat/plugin-sdk';
import { array, at, items, number, object, plain, text } from './util';

export const LIKED: EntityRef = { kind: 'PLAYLIST', providerId: 'spotify:collection:tracks' };

export function reference(value: unknown): EntityRef | undefined {
  const id = text(value);
  if (id === LIKED.providerId) return LIKED;
  const match = id?.match(/^spotify:(track|album|artist|playlist):[A-Za-z0-9]{22}$/);
  return match ? { kind: match[1].toUpperCase() as EntityRef['kind'], providerId: id! } : undefined;
}

export function artwork(value: unknown): Artwork | undefined {
  const source = items(value).flatMap((v) => array(at(v, 'sources')));
  const candidates = [...array(at(value, 'sources')), ...source].map(object).filter((v) => text(v.url)?.startsWith('https://'));
  const best = candidates.sort((a, b) => (number(b.width) ?? 640) - (number(a.width) ?? 640))[0];
  return best ? { url: String(best.url) } : undefined;
}

export function credits(value: unknown): ArtistCredit[] {
  return items(value).flatMap((v) => {
    const artist = object(at(v, 'data') ?? v);
    const name = text(at(artist, 'profile', 'name')) ?? text(artist.name);
    const entity = reference(artist.uri);
    return name ? [{ name, entity: entity?.kind === 'ARTIST' ? entity : undefined }] : [];
  });
}

function unwrap(value: unknown): { data: Record<string, unknown>; uri?: string } {
  let data = object(value);
  let id: string | undefined;
  for (let i = 0; i < 5; i++) {
    id = text(data._uri) ?? text(data.uri) ?? id;
    if (reference(id) && (text(data.name) || text(at(data, 'profile', 'name')))) break;
    const nested = data.itemV2 ?? data.item ?? data.track ?? data.content ?? data.data;
    if (nested === undefined || nested === null) break;
    data = object(nested);
  }
  return { data, uri: text(data.uri) ?? id };
}

export function trackDescriptor(value: unknown, albumOverride?: unknown): TrackDescriptor | undefined {
  const { data, uri } = unwrap(value);
  const ref = reference(uri);
  const title = text(data.name);
  if (!ref || ref.kind !== 'TRACK' || !title || at(data, 'playability', 'playable') === false) return undefined;
  const album = object(data.albumOfTrack ?? albumOverride);
  const year = number(at(album, 'date', 'year')) ?? Number(text(at(album, 'date', 'isoString'))?.slice(0, 4));
  const durationMs = number(at(data, 'trackDuration', 'totalMilliseconds')) ?? number(at(data, 'duration', 'totalMilliseconds')) ?? number(data.durationMs);
  const isrc = text(at(data, 'externalIds', 'isrc')) ?? text(at(data, 'external_ids', 'isrc'));
  const artists = credits(data.artists);
  return {
    ref, title,
    artists: artists.length ? artists : credits({ items: [...items(data.firstArtist), ...items(data.otherArtists)] }),
    album: text(album.name),
    albumRef: reference(album.uri),
    durationMs,
    explicit: at(data, 'contentRating', 'label') === 'EXPLICIT',
    artwork: artwork(album.coverArt),
    trackNumber: number(data.trackNumber),
    discNumber: number(data.discNumber),
    year: Number.isFinite(year) && year > 0 ? year : undefined,
    ids: isrc ? { isrc } : undefined,
  };
}

export function trackItem(track: TrackDescriptor, ordinal?: number): MetadataItem {
  return {
    id: `${track.ref.providerId}/${ordinal ?? 0}`, entity: track.ref, title: track.title,
    subtitle: track.artists?.map((a) => a.name).join(', ') || undefined,
    artwork: track.artwork, view: 'TRACK_ROW', artists: track.artists,
    durationSeconds: track.durationMs ? Math.round(track.durationMs / 1000) : undefined,
    explicit: track.explicit, ordinal, album: track.album, track,
  };
}

export function card(value: unknown): MetadataItem | undefined {
  const { data, uri } = unwrap(value);
  const entity = reference(uri);
  if (!entity) return undefined;
  if (entity.kind === 'TRACK') {
    const track = trackDescriptor(value);
    return track ? trackItem(track) : undefined;
  }
  const title = text(at(data, 'profile', 'name')) ?? text(data.name);
  if (!title) return undefined;
  const artists = credits(data.artists);
  return {
    id: entity.providerId, entity, title, artists,
    subtitle: entity.kind === 'ARTIST' ? 'Artist' : entity.kind === 'ALBUM' ? artists.map((a) => a.name).join(', ') || 'Album' : plain(data.description) ?? text(at(data, 'ownerV2', 'data', 'name')),
    artwork: artwork(entity.kind === 'ARTIST' ? at(data, 'visuals', 'avatarImage') : entity.kind === 'ALBUM' ? data.coverArt : data.images),
    view: entity.kind === 'ARTIST' ? 'ARTIST_PORTRAIT' : 'COVER_CARD',
  };
}

export function cards(values: unknown[]): MetadataItem[] {
  const seen = new Set<string>();
  return values.flatMap((value) => {
    const item = card(value);
    if (!item || seen.has(item.id)) return [];
    seen.add(item.id);
    return [item];
  });
}
