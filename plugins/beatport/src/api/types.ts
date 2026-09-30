// The parts of Beatport's v4 JSON the plugin reads. Every field is optional: the API omits and nulls
// fields freely, and the mapping must survive either.

export interface Paginated<T> {
  count?: number;
  next?: string | null;
  previous?: string | null;
  page?: string;
  per_page?: number;
  results?: T[];
}

/** `uri` is a fixed size; `dynamic_uri` holds a `{w}x{h}` template. */
export interface Image {
  uri?: string;
  dynamic_uri?: string;
}

export interface Named {
  id: number;
  name?: string;
  slug?: string;
}

export interface Person extends Named {
  image?: Image | null;
}

export interface Label extends Named {
  image?: Image | null;
  bio?: string | null;
}

export interface Artist extends Person {
  bio?: string | null;
  /** The artist's DJ profile, whose charts are the artist's charts. */
  dj_association?: number | null;
}

export interface Genre extends Named {
  category?: { id?: number; name?: string } | null;
  sub_genres?: Named[];
}

export interface Key {
  name?: string;
  camelot_number?: number;
  camelot_letter?: string;
}

export interface ReleaseRef extends Named {
  image?: Image | null;
  label?: Label | null;
}

export interface Track extends Named {
  mix_name?: string | null;
  artists?: Person[];
  remixers?: Person[];
  release?: ReleaseRef | null;
  label?: Label | null;
  genre?: Named | null;
  sub_genre?: Named | null;
  bpm?: number | null;
  key?: Key | null;
  isrc?: string | null;
  length_ms?: number | null;
  is_explicit?: boolean;
  is_available_for_streaming?: boolean;
  new_release_date?: string | null;
  publish_date?: string | null;
}

/** A row of a playlist's tracks: the track, wrapped with its position. */
export interface PlaylistEntry {
  id?: number;
  position?: number;
  track?: Track;
}

export interface Release extends Named {
  artists?: Person[];
  remixers?: Person[];
  label?: Label | null;
  image?: Image | null;
  catalog_number?: string | null;
  new_release_date?: string | null;
  publish_date?: string | null;
  bpm_range?: { min?: number; max?: number } | null;
  track_count?: number;
  desc?: string | null;
  type?: { id?: number; name?: string } | null;
}

export interface Chart extends Named {
  image?: Image | null;
  person?: { id?: number; owner_name?: string; owner_image?: string } | null;
  artist?: Named | null;
  genres?: Named[];
  track_count?: number;
  publish_date?: string | null;
  description?: string | null;
}

/** A playlist as lists show it; the detail adds counts, genres and the covers of its releases. */
export interface Playlist extends Named {
  genre?: Named | null;
  genres?: string[];
  track_count?: number;
  length_ms?: number;
  bpm_range?: number[] | null;
  release_images?: string[];
  type?: number | { id?: number; name?: string } | null;
  is_public?: boolean;
  created_date?: string;
  updated_date?: string;
}

/** `catalog/search/` without a type answers every kind at once, with the kinds' order. */
export interface SearchResults {
  order?: string[];
  tracks?: Track[];
  artists?: Artist[];
  releases?: Release[];
  labels?: Label[];
  charts?: Chart[];
  playlists?: Playlist[];
  count?: number;
  next?: string | null;
}

/** One pick of `/catalog/v1/recommendations/user/`, which has its own schema. */
export interface Recommendation {
  track_id: number;
  track_name?: string;
  mix_name?: string | null;
  artists?: { id: number; name?: string }[];
  bpm?: number | null;
  key?: string | null;
  isrc?: string | null;
  genre?: { id?: number; name?: string } | null;
  label?: { id: number; name?: string } | null;
  release?: { id: number; name?: string; image_url?: string; release_date?: string } | null;
  track_length_ms?: number | null;
  availability?: { tags?: string[] } | null;
}

/** An artist or label the listener follows, with how many of their tracks the feed holds. */
export interface FollowedEntry extends Named {
  image?: Image | null;
  count?: number;
}

/** One entry of a page module: a release, chart or track, or for a banner, a picture linking somewhere on the store. */
export interface PageModuleItem {
  item_type?: { name?: string } | null;
  item?: (Release & Chart & Track) | null;
  image?: Image | null;
  external_url?: string | null;
}

/** A curated module of a Beatport page (a genre's banners, hype picks, staff picks, charts). */
export interface PageModule {
  id: number;
  name?: string;
  enabled?: boolean;
  type?: { name?: string } | null;
  items?: PageModuleItem[];
}

/** A curated page: a genre has one per Beatport surface (the web store is `sushi`). */
export interface CurationPage {
  id: number;
  name?: string;
  type?: { name?: string } | null;
  source_type?: { name?: string } | null;
}
