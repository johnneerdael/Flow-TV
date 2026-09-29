// Radio exactly as the app continues a queue (Media3MusicService: startRadio, trackMix, collectionMix,
// radioPage, toRadioTracks), a behaviour tuned against Metrolist: a track continues with its RDAMVM
// mix, a collection with the automix its watch queue names, read on its own so the collection is not
// played again; always YouTube's own order, as the signed-in listener first when there is one.
import type { EntityRef, RadioRequest, TrackDescriptor, TrackList } from '@milkbeat/plugin-sdk';
import { fail, mb } from '@milkbeat/plugin-sdk';
import { type WatchEndpoint, browse, next, signedIn } from './api';
import { decodeCursor, encodeCursor } from './cursor';
import { collectionHeader, collectionPlaylistId } from './pages';
import { type WatchPage, watchPage } from './watch';

const ARTIST_STATION_PREFIX = 'RDEM';

interface RadioPage {
  tracks: TrackDescriptor[];
  /** The queue's own tracks, before the automix YouTube continued it with. */
  own: TrackDescriptor[];
  continuation?: string;
  /** The endpoint a continuation continues: the automix's when the queue ran into one. */
  endpoint: WatchEndpoint;
}

async function read(endpoint: WatchEndpoint, continuation: string | undefined, auth: boolean): Promise<RadioPage> {
  const page: WatchPage | undefined = watchPage(await next(endpoint, continuation, auth));
  if (!page) fail('NOT_FOUND', `No watch queue for ${endpoint.playlistId ?? endpoint.videoId}`);
  if (page.automix) {
    const rest = await read(page.automix, undefined, auth);
    return { tracks: [...page.tracks, ...rest.tracks], own: page.tracks, continuation: rest.continuation, endpoint: page.automix };
  }
  return { tracks: page.tracks, own: page.tracks, continuation: page.continuation, endpoint };
}

/** The account's own mix when signed in, as YouTube Music would queue it; the anonymous one otherwise. */
async function radioPage(endpoint: WatchEndpoint, continuation?: string): Promise<RadioPage | undefined> {
  if (await signedIn()) {
    try {
      return await read(endpoint, continuation, true);
    } catch (error) {
      await mb.log.write({ level: 'WARN', message: `Signed-in mix unavailable, using the anonymous one: ${String((error as Error)?.message ?? error)}` });
    }
  }
  try {
    return await read(endpoint, continuation, false);
  } catch {
    return undefined;
  }
}

async function trackMix(seedId: string): Promise<RadioPage | undefined> {
  const page = await radioPage({ videoId: seedId, playlistId: `RDAMVM${seedId}` });
  if (page && page.tracks.length > 1) return page;
  return (await radioPage({ videoId: seedId })) ?? page;
}

/**
 * A collection's similar content, as Metrolist's getAutomix reads it: the collection's watch queue
 * names its automix playlist, which is then read on its own. Read through the watch queue instead,
 * the automix opens with the collection's own tracks again under other ids.
 */
async function collectionMix(playlistId: string): Promise<{ page?: RadioPage; lastTrack?: string }> {
  const watch = await radioPage({ playlistId });
  const lastTrack = watch?.own[watch.own.length - 1]?.ref.providerId;
  // An artist's station is already the similar content itself; other RD playlists (curated,
  // personal mixes) have their own automix like any playlist.
  if (playlistId.startsWith(ARTIST_STATION_PREFIX)) return { page: watch, lastTrack };
  const mixId = watch?.endpoint.playlistId !== playlistId ? watch?.endpoint.playlistId : undefined;
  return { page: (await radioPage({ playlistId: mixId ?? `RDAMPL${playlistId}` })) ?? watch, lastTrack };
}

function toRadioTracks(tracks: TrackDescriptor[], seedId: string | undefined): TrackDescriptor[] {
  const seen = new Set<string>();
  return tracks.filter((track) => {
    const id = track.ref.providerId;
    if (id === seedId || seen.has(id)) return false;
    seen.add(id);
    return true;
  });
}

function trackList(page: RadioPage | undefined, seedId: string | undefined): TrackList {
  if (!page) return { tracks: [] };
  const tracks = toRadioTracks(page.tracks, seedId);
  const playlistId = page.endpoint.playlistId;
  return {
    tracks,
    next: page.continuation && tracks.length > 0 ? encodeCursor({ type: 'radio', endpoint: page.endpoint, continuation: page.continuation }) : undefined,
    source: playlistId ? { kind: 'RADIO', providerId: playlistId } : undefined,
  };
}

/** The playlist an album plays as, read from its page. */
async function albumPlaylist(album: EntityRef): Promise<string> {
  const header = collectionHeader(await browse({ browseId: album.providerId }));
  const id = header ? collectionPlaylistId(header, album) : undefined;
  if (!id) fail('NOT_FOUND', `No playlist for album ${album.providerId}`);
  return id;
}

async function collectionRadio(playlistId: string): Promise<TrackList> {
  const { page, lastTrack } = await collectionMix(playlistId);
  if (page && page.tracks.length > 0) return trackList(page, lastTrack);
  // As the app: a collection without a mix hands over to the mix of its last track.
  return lastTrack ? trackList(await trackMix(lastTrack), lastTrack) : { tracks: [] };
}

export async function radio(request: RadioRequest): Promise<TrackList> {
  if (request.cursor) {
    const cursor = decodeCursor(request.cursor, 'radio');
    return trackList(await radioPage(cursor.endpoint, cursor.continuation), undefined);
  }
  const { seed } = request;
  switch (seed.kind) {
    case 'TRACK':
    case 'MUSIC_VIDEO':
      return trackList(await trackMix(seed.providerId), seed.providerId);
    case 'ALBUM':
      return collectionRadio(await albumPlaylist(seed));
    case 'PLAYLIST':
    case 'MIX':
      return collectionRadio(seed.providerId);
    case 'RADIO':
      return trackList(await radioPage({ playlistId: seed.providerId }), undefined);
    default:
      return fail('UNSUPPORTED', `No radio from ${seed.kind}`);
  }
}
