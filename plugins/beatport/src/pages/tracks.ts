// A Beatport track as the host queues it and as a row shows it. The title carries the mix name, as
// Beatport prints it ("Numb (Original Mix)"); remixers are credited after the artists. A row's
// details follow the web store's columns: label, remixers, genre, BPM and key, release date.
import type { ArtistCredit, MetadataItem, TrackDescriptor } from '@milkbeat/plugin-sdk';
import type { Person, Recommendation, Track } from '../api/types';
import { distinctBy, isPresent } from '../util';
import { CARD, LARGE, artwork } from './artwork';
import { refs } from './refs';

export function trackTitle(track: Pick<Track, 'name' | 'mix_name'>): string {
  const name = track.name?.trim() ?? '';
  const mix = track.mix_name?.trim();
  return mix ? `${name} (${mix})` : name;
}

const names = (people: Person[] | undefined) => (people ?? []).map((person) => person.name).filter(isPresent);

function credits(track: Track): ArtistCredit[] {
  const people = distinctBy([...(track.artists ?? []), ...(track.remixers ?? [])], (person) => String(person.id));
  return people.filter((person) => person.name).map((person) => ({ name: person.name as string, entity: refs.artist(person.id) }));
}

function year(date: string | null | undefined): number | undefined {
  const value = Number(date?.slice(0, 4));
  return Number.isInteger(value) && value > 0 ? value : undefined;
}

export function releaseDate(track: Pick<Track, 'new_release_date' | 'publish_date'>): string | undefined {
  return (track.new_release_date ?? track.publish_date ?? undefined)?.slice(0, 10);
}

/** "126 BPM · Eb Minor", or whichever half Beatport knows. */
export function tempo(bpm: number | null | undefined, key: string | null | undefined): string | undefined {
  const parts = [bpm ? `${bpm} BPM` : undefined, key || undefined].filter(isPresent);
  return parts.length > 0 ? parts.join(' · ') : undefined;
}

export function trackDescriptor(track: Track): TrackDescriptor {
  const ids: Record<string, string> = { beatport: String(track.id) };
  if (track.isrc) ids.isrc = track.isrc;
  return {
    ref: refs.track(track.id),
    title: trackTitle(track),
    artists: credits(track),
    album: track.release?.name,
    albumRef: track.release ? refs.release(track.release.id) : undefined,
    durationMs: track.length_ms ?? undefined,
    explicit: track.is_explicit === true,
    artwork: artwork(track.release?.image, LARGE),
    year: year(releaseDate(track)),
    ids,
  };
}

function details(track: Track): string[] {
  const remixers = names(track.remixers);
  return [
    (track.release?.label ?? track.label)?.name,
    remixers.length > 0 ? `Remixers: ${remixers.join(', ')}` : undefined,
    track.genre?.name,
    tempo(track.bpm, track.key?.name),
    releaseDate(track),
  ].filter(isPresent);
}

/** A row of the block [blockId]; [ordinal] numbers it in a chart or a release. */
export function trackItem(track: Track, blockId: string, ordinal?: number): MetadataItem {
  const descriptor = trackDescriptor(track);
  return {
    id: `${blockId}#${track.id}`,
    entity: descriptor.ref,
    title: descriptor.title,
    subtitle: names(track.artists).join(', ') || undefined,
    artwork: artwork(track.release?.image, CARD),
    view: 'TRACK_ROW',
    artists: descriptor.artists,
    durationSeconds: track.length_ms ? Math.round(track.length_ms / 1000) : undefined,
    explicit: descriptor.explicit,
    ordinal,
    album: track.release?.name,
    details: details(track),
    track: descriptor,
  };
}

/** Rows for [tracks], numbered from [firstOrdinal] when given; a track listed twice keeps its first row. */
export function trackItems(tracks: Track[], blockId: string, firstOrdinal?: number): MetadataItem[] {
  const items = tracks.map((track, index) => trackItem(track, blockId, firstOrdinal === undefined ? undefined : firstOrdinal + index));
  return distinctBy(items, (item) => item.id);
}

/** A pick of the recommendations service, in the catalog's track shape. */
export function recommendedTrack(pick: Recommendation): Track {
  const image = pick.release?.image_url ? { dynamic_uri: pick.release.image_url } : undefined;
  return {
    id: pick.track_id,
    name: pick.track_name,
    mix_name: pick.mix_name,
    artists: (pick.artists ?? []).map((artist) => ({ id: artist.id, name: artist.name })),
    release: pick.release ? { id: pick.release.id, name: pick.release.name, image, label: pick.label ? { id: pick.label.id, name: pick.label.name } : undefined } : undefined,
    genre: pick.genre?.id ? { id: pick.genre.id, name: pick.genre.name } : undefined,
    bpm: pick.bpm,
    key: pick.key ? { name: pick.key } : undefined,
    isrc: pick.isrc,
    length_ms: pick.track_length_ms,
    is_available_for_streaming: pick.availability?.tags?.includes('available_for_streaming'),
    new_release_date: pick.release?.release_date?.slice(0, 10),
  };
}
