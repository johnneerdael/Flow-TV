import type { Attribution, MetadataPage, PageBlockHeader, PageRequest, TrackList, TracksRequest } from '@milkbeat/plugin-sdk';
import { mb } from '@milkbeat/plugin-sdk';
import { collectionData, descriptors } from './catalog';
import { artwork, credits } from './mapping';
import { collection, present, shelf, trackRows } from './pages';
import { at, items, nextCursor, number, object, offset, plain, text } from './util';
import { homeSection } from './home';

export async function tracks(request: TracksRequest): Promise<TrackList> {
  const start = offset(request.cursor, request.entity.providerId);
  const result = await collectionData(request.entity, start);
  return { tracks: descriptors(result), source: request.entity, next: nextCursor(request.entity.providerId, start, result.rows.length, result.total) };
}

export async function entity(request: PageRequest): Promise<MetadataPage> {
  const ref = request.entity;
  if (ref.kind === 'MIX' && ref.providerId.startsWith('spotify:section:')) return homeSection(request);
  const start = offset(request.cursor, ref.providerId);
  const result = await collectionData(ref, start);
  const data = result.data;
  const artist = ref.kind === 'ARTIST';
  const artistCredits = credits(data.artists);
  const owner = object(at(data, 'ownerV2', 'data'));
  const attribution: Attribution | undefined = artistCredits[0] ?? (text(owner.name) ? { name: String(owner.name), avatar: artwork(owner.avatar) } : undefined);
  const count = number(result.total);
  const locale = (await mb.env.get()).locale;
  const audience = number(at(data, 'stats', 'monthlyListeners'));
  const details = artist
    ? [audience !== undefined ? `${audience.toLocaleString(locale)} monthly listeners` : undefined]
    : [count !== undefined ? `${count} songs` : undefined, text(at(data, 'date', 'isoString'))?.slice(0, 10)];
  const header: PageBlockHeader | undefined = start === 0 ? {
    type: 'header', id: `${ref.providerId}/header`, style: artist ? 'PORTRAIT' : 'COVER', entity: ref,
    title: text(at(data, 'profile', 'name')) ?? text(data.name) ?? 'Spotify',
    artwork: artwork(artist ? at(data, 'visuals', 'avatarImage') : ref.kind === 'ALBUM' ? data.coverArt : data.images),
    details: details.filter((d): d is string => Boolean(d)), attribution,
    description: plain(artist ? at(data, 'profile', 'biography', 'text') : data.description),
    tracks: ref.kind === 'TRACK' ? undefined : ref,
  } : undefined;
  const related = start > 0 ? [] : artist ? [
    shelf('releases', 'Popular releases', items(at(data, 'discography', 'popularReleasesAlbums'))),
    shelf('albums', 'Albums', items(at(data, 'discography', 'albums')).map((v) => at(v, 'releases', 'items') ? items(at(v, 'releases'))[0] : v)),
    shelf('singles', 'Singles and EPs', items(at(data, 'discography', 'singles')).map((v) => at(v, 'releases', 'items') ? items(at(v, 'releases'))[0] : v)),
    shelf('featuring', 'Featuring', items(at(data, 'relatedContent', 'featuringV2'))),
    shelf('discovered-on', 'Discovered on', items(at(data, 'relatedContent', 'discoveredOnV2'))),
    shelf('related-artists', 'Fans also like', items(at(data, 'relatedContent', 'relatedArtists'))),
  ] : ref.kind === 'ALBUM' ? [shelf('more-albums', 'More by the artist', items(at(data, 'moreAlbumsByArtist')))] : [];
  return {
    id: `spotify/${ref.providerId}`,
    blocks: present([header, collection('tracks', artist ? 'Popular' : undefined, trackRows(result.rows, start, result.album), true), ...related]),
    nextCursor: nextCursor(ref.providerId, start, result.rows.length, result.total),
  };
}
