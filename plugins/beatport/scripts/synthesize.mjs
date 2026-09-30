// Stand-ins for the listener's own endpoints, in their recorded shapes but built from public catalog
// data and made-up names and ids, so the fixtures hold nothing of the account that recorded them.
import { IDS } from './scenario.mjs';

const FAKE_DATE = '2026-01-01T00:00:00-07:00';
const PLAYLIST_IDS = [900001, 900002, 900003];
const PLAYLIST_NAMES = ['Playlist A', 'Playlist B', 'Playlist C'];
/** The scenario's artist, followed; its label is not. */
const FOLLOWED_ARTIST = IDS.artist;

const page = (results, count, next) => ({ count, next, previous: null, page: next ? '1/2' : '2/2', per_page: results.length, results });

function recommendation(track) {
  return {
    track_id: track.id,
    track_name: track.name,
    mix_name: track.mix_name,
    artists: (track.artists ?? []).map((artist) => ({ id: artist.id, name: artist.name, type: 'Artist' })),
    bpm: track.bpm,
    key: track.key?.name,
    isrc: track.isrc,
    genre: track.genre ? { id: track.genre.id, name: track.genre.name } : null,
    label: track.release?.label ? { id: track.release.label.id, name: track.release.label.name } : null,
    release: track.release ? { id: track.release.id, name: track.release.name, image_url: track.release.image?.dynamic_uri, release_date: `${track.new_release_date}T00:00:00` } : null,
    track_length_ms: track.length_ms,
    availability: { tags: ['available_for_streaming'] },
  };
}

function playlist(index, images) {
  return {
    id: PLAYLIST_IDS[index],
    name: PLAYLIST_NAMES[index],
    created_date: FAKE_DATE,
    updated_date: FAKE_DATE,
    track_count: 4,
    type: { id: 2, name: 'user' },
    length_ms: 1_500_000,
    bpm_range: [120, 128],
    genres: ['House'],
    keys: [],
    release_images: images,
    is_public: false,
    is_owner: true,
    followers: 0,
  };
}

/** The answer to [url], a private endpoint, from [publicGet] (a trimmed, signed GET of a public path). */
export async function synthesize(url, publicGet) {
  const path = url.pathname;
  const pageNumber = Number(url.searchParams.get('page') ?? '1');
  const top = (await publicGet('/v4/catalog/tracks/top/100/?per_page=4')).results;
  const images = top.map((track) => track.release?.image?.uri).filter(Boolean);
  if (path === '/catalog/v1/recommendations/user/') return { status: 200, body: top.map(recommendation) };
  if (path === '/v4/my/playlists/') {
    return { status: 200, body: page(pageNumber === 1 ? PLAYLIST_IDS.map((_, index) => playlist(index, images)) : [], 60, pageNumber === 1 ? 'next' : null) };
  }
  const own = path.match(/^\/v4\/my\/playlists\/(\d+)\/(tracks\/)?$/);
  if (own && PLAYLIST_IDS.includes(Number(own[1]))) {
    const index = PLAYLIST_IDS.indexOf(Number(own[1]));
    if (!own[2]) return { status: 200, body: playlist(index, images) };
    return { status: 200, body: page(top.map((track, position) => ({ id: position + 1, position: position + 1, track })), top.length, null) };
  }
  if (path === '/v4/my/beatport/') {
    return { status: 200, body: { count: 1, next: null, previous: null, page: '1/1', per_page: 5000, results: { performer: [Number(FOLLOWED_ARTIST)], label: [], playlist: [] } } };
  }
  if (path === '/v4/my/beatport/tracks/') {
    const genreTop = (await publicGet(`/v4/catalog/genres/90/top/100/?per_page=4&page=${pageNumber}`)).results;
    return { status: 200, body: page(genreTop, 144, pageNumber === 1 ? 'next' : null) };
  }
  if (path === '/v4/my/beatport/artists/') {
    const artists = top.flatMap((track) => track.artists ?? []).slice(0, 3);
    return { status: 200, body: artists.map((artist) => ({ id: artist.id, name: artist.name, count: 1, image: artist.image })) };
  }
  if (path === '/v4/my/beatport/labels/') {
    const labels = top.map((track) => track.release?.label).filter(Boolean).slice(0, 3);
    return { status: 200, body: labels.map((label) => ({ id: label.id, name: label.name, count: 1, image: label.image })) };
  }
  return { status: 404, body: { detail: 'Not recorded: a private endpoint with no stand-in' } };
}
