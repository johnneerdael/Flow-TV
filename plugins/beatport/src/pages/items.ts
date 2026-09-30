// Cards and rows for everything that is not a track: releases, artists, labels, charts, playlists
// and genres. [blockId] makes each occurrence's id unique on its page.
import type { MetadataItem } from '@milkbeat/plugin-sdk';
import type { PlaylistSource } from '../api/playlists';
import type { Chart, FollowedEntry, Genre, Label, Person, Playlist, Release } from '../api/types';
import { counted, isPresent } from '../util';
import { CARD, artwork, fixed } from './artwork';
import { releaseDate, tempo } from './tracks';
import { refs } from './refs';

const names = (people: Person[] | undefined) => (people ?? []).map((person) => person.name).filter(isPresent);

export function bpmRange(range: Release['bpm_range'] | Playlist['bpm_range']): string | undefined {
  const [min, max] = Array.isArray(range) ? range : [range?.min, range?.max];
  if (!min && !max) return undefined;
  return !min || !max || min === max ? tempo(min || max, undefined) : `${min}–${max} BPM`;
}

export function releaseItem(release: Release, blockId: string, ordinal?: number): MetadataItem {
  const artists = release.artists ?? [];
  return {
    id: `${blockId}#${release.id}`,
    entity: refs.release(release.id),
    title: release.name ?? '',
    subtitle: names(artists).join(', ') || undefined,
    artwork: artwork(release.image, CARD),
    view: 'COVER_CARD',
    artists: artists.filter((artist) => artist.name).map((artist) => ({ name: artist.name as string, entity: refs.artist(artist.id) })),
    ordinal,
    details: [release.label?.name, releaseDate(release)].filter(isPresent),
  };
}

export function artistItem(artist: Person | FollowedEntry, blockId: string): MetadataItem {
  return {
    id: `${blockId}#${artist.id}`,
    entity: refs.artist(artist.id),
    title: artist.name ?? '',
    artwork: artwork(artist.image, CARD),
    view: 'ARTIST_PORTRAIT',
  };
}

export function labelItem(label: Label | FollowedEntry, blockId: string): MetadataItem {
  return {
    id: `${blockId}#${label.id}`,
    entity: refs.label(label.id),
    title: label.name ?? '',
    artwork: artwork(label.image, CARD),
    view: 'COVER_CARD',
  };
}

export function chartItem(chart: Chart, blockId: string): MetadataItem {
  const curator = chart.person?.owner_name ?? chart.artist?.name;
  return {
    id: `${blockId}#${chart.id}`,
    entity: refs.chart(chart.id),
    title: chart.name ?? '',
    subtitle: curator,
    artwork: artwork(chart.image, CARD),
    view: 'COVER_CARD',
    artists: curator && chart.artist ? [{ name: curator, entity: refs.artist(chart.artist.id) }] : [],
    details: [chart.genres?.map((genre) => genre.name).filter(isPresent).join(', ') || undefined, chart.publish_date?.slice(0, 10)].filter(isPresent),
  };
}

/** Lists of playlists carry no covers; the detail and the listener's own list carry their releases'. */
export function playlistItem(playlist: Playlist, source: PlaylistSource, blockId: string): MetadataItem {
  const genres = playlist.genre?.name ?? playlist.genres?.join(', ');
  return {
    id: `${blockId}#${playlist.id}`,
    entity: refs.playlist(source, playlist.id),
    title: playlist.name ?? '',
    subtitle: genres || undefined,
    artwork: fixed(playlist.release_images?.[0], CARD),
    view: 'COVER_CARD',
    details: [playlist.track_count !== undefined ? counted(playlist.track_count, 'track') : undefined, (playlist.updated_date ?? playlist.created_date)?.slice(0, 10)].filter(isPresent),
  };
}

export function genreItem(genre: Genre, blockId: string): MetadataItem {
  return {
    id: `${blockId}#${genre.id}`,
    entity: refs.genre(genre.id),
    title: genre.name ?? '',
    subtitle: genre.category?.name,
    view: 'COVER_CARD',
  };
}
