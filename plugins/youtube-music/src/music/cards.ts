// A carousel card (musicTwoRowItemRenderer) as a catalog item, ported from the app's
// HomePage.fromMusicTwoRowItemRenderer and LibraryPage's lenient variant. Cards the app could not
// read (podcast episodes, cards missing what the app requires) are left out the same way.
import type { ArtistCredit, EntityRef, MetadataItem, TrackDescriptor } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import {
  firstRun,
  isExplicit,
  isVideoType,
  menuEndpoint,
  musicVideoType,
  oddElements,
  pageType,
  playEndpoint,
  runsText,
  stripVl,
  thumbnailUrl,
} from './renderers';

const ASPECT_RATIO_16_9 = 'MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9';

interface Card {
  entity: EntityRef;
  title: string;
  thumbnail: string;
  artists: ArtistCredit[];
  explicit: boolean;
  album?: { name: string; id: string };
}

function artistCredit(run: Json): ArtistCredit {
  const id: string | undefined = run?.navigationEndpoint?.browseEndpoint?.browseId;
  return id ? { name: run.text ?? '', entity: { kind: 'ARTIST', providerId: id } } : { name: run.text ?? '' };
}

function song(renderer: Json, thumbnail: string): Card | undefined {
  const runs: Json[] | undefined = renderer.subtitle?.runs;
  if (!runs) return undefined;
  const isArtist = (run: Json) => String(run?.navigationEndpoint?.browseEndpoint?.browseId ?? '').startsWith('UC');
  const videoId: string | undefined = renderer.navigationEndpoint?.watchEndpoint?.videoId;
  const title = firstRun(renderer.title);
  if (!videoId || title === undefined) return undefined;
  const albumRun = runs.find((run) => !isArtist(run) && String(run?.navigationEndpoint?.browseEndpoint?.browseId ?? '').startsWith('MPREb_'));
  const videoType = musicVideoType(playEndpoint(renderer.thumbnailOverlay)) ?? musicVideoType(renderer.navigationEndpoint);
  return {
    entity: { kind: isVideoType(videoType) ? 'MUSIC_VIDEO' : 'TRACK', providerId: videoId },
    title,
    thumbnail,
    artists: runs.filter(isArtist).map(artistCredit),
    explicit: isExplicit(renderer.subtitleBadges),
    album: albumRun ? { name: albumRun.text, id: albumRun.navigationEndpoint.browseEndpoint.browseId } : undefined,
  };
}

function album(renderer: Json, browseId: string, thumbnail: string): Card | undefined {
  const playlistId = playEndpoint(renderer.thumbnailOverlay)?.watchPlaylistEndpoint?.playlistId;
  const title = firstRun(renderer.title);
  if (!playlistId || title === undefined) return undefined;
  const runs: Json[] = renderer.subtitle?.runs ?? [];
  return {
    entity: { kind: 'ALBUM', providerId: browseId },
    title,
    thumbnail,
    artists: oddElements(runs).slice(1).map(artistCredit),
    explicit: isExplicit(renderer.subtitleBadges),
  };
}

function playlist(renderer: Json, browseId: string, thumbnail: string, lenient: boolean): Card | undefined {
  const title = firstRun(renderer.title);
  const author: string | undefined = renderer.subtitle?.runs?.[renderer.subtitle.runs.length - 1]?.text;
  if (title === undefined) return undefined;
  if (!lenient) {
    const playable = playEndpoint(renderer.thumbnailOverlay)?.watchPlaylistEndpoint;
    if (author === undefined || !playable || !menuEndpoint(renderer.menu, 'MUSIC_SHUFFLE')) return undefined;
  }
  return {
    entity: { kind: 'PLAYLIST', providerId: stripVl(browseId) },
    title,
    thumbnail,
    artists: !lenient && author !== undefined ? [{ name: author }] : [],
    explicit: false,
  };
}

function artist(renderer: Json, browseId: string, thumbnail: string): Card | undefined {
  const runs: Json[] | undefined = renderer.title?.runs;
  const title: string | undefined = runs?.[runs.length - 1]?.text;
  if (title === undefined || !menuEndpoint(renderer.menu, 'MUSIC_SHUFFLE') || !menuEndpoint(renderer.menu, 'MIX')) return undefined;
  return { entity: { kind: 'ARTIST', providerId: browseId }, title, thumbnail, artists: [], explicit: false };
}

function parse(renderer: Json, lenient: boolean): Card | undefined {
  const thumbnail = thumbnailUrl(renderer.thumbnailRenderer);
  if (!thumbnail) return undefined;
  const endpoint = renderer.navigationEndpoint;
  if (endpoint?.watchEndpoint || endpoint?.watchPlaylistEndpoint) return lenient ? undefined : song(renderer, thumbnail);
  const browseId: string | undefined = endpoint?.browseEndpoint?.browseId;
  if (!browseId) return undefined;
  switch (pageType(endpoint.browseEndpoint)) {
    case 'MUSIC_PAGE_TYPE_ALBUM':
    case 'MUSIC_PAGE_TYPE_AUDIOBOOK':
      return album(renderer, browseId, thumbnail);
    case 'MUSIC_PAGE_TYPE_PLAYLIST':
      return playlist(renderer, browseId, thumbnail, lenient);
    case 'MUSIC_PAGE_TYPE_ARTIST':
      return artist(renderer, browseId, thumbnail);
    default:
      return undefined;
  }
}

/**
 * A card in collection [blockId]. [lenient] reads a library grid, whose playlists carry no author or
 * play buttons the home's do.
 */
export function card(renderer: Json, blockId: string, lenient = false): MetadataItem | undefined {
  const parsed = parse(renderer, lenient);
  if (!parsed) return undefined;
  const { entity } = parsed;
  const playable = entity.kind === 'TRACK' || entity.kind === 'MUSIC_VIDEO';
  const artwork = { url: parsed.thumbnail };
  const track: TrackDescriptor | undefined = playable
    ? {
        ref: entity,
        title: parsed.title,
        artists: parsed.artists,
        album: parsed.album?.name,
        albumRef: parsed.album ? { kind: 'ALBUM', providerId: parsed.album.id } : undefined,
        explicit: parsed.explicit,
        artwork,
        hasVideo: entity.kind === 'MUSIC_VIDEO',
        ids: { ytm: entity.providerId },
      }
    : undefined;
  return {
    id: `${blockId}#${entity.providerId}`,
    entity,
    title: parsed.title,
    subtitle: runsText(renderer.subtitle),
    artwork,
    view: renderer.aspectRatio === ASPECT_RATIO_16_9 ? 'LANDSCAPE_CARD' : entity.kind === 'ARTIST' ? 'ARTIST_PORTRAIT' : undefined,
    artists: parsed.artists,
    explicit: parsed.explicit,
    track,
  };
}
