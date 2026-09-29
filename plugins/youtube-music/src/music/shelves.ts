// YouTube Music's shelves as catalog collections, shared by every page, ported from the app's
// YouTubeShelfMapper: what a shelf says about itself (its context line and avatar, whether it lists
// tracks, which items are wide videos) is kept.
import type { ArtistCredit, EntityRef, MetadataItem, PageBlock, PageBlockCollection } from '@milkbeat/plugin-sdk';
import type { Json } from '../util';
import { card } from './cards';
import { anyWatchEndpoint, browseRef, runsText, thumbnailUrl } from './renderers';
import { row, rows, sameEntity } from './rows';

/** A playlist id as an entity: generated mixes (RD…, except curated RDCLAK playlists) are mixes. */
export function playlistRef(playlistId: string): EntityRef {
  const mix = playlistId.startsWith('RD') && !playlistId.startsWith('RDCLAK');
  return { kind: mix ? 'MIX' : 'PLAYLIST', providerId: playlistId };
}

/**
 * Where a shelf's More or Play all button leads, when that is a collection of its own; a button that
 * opens a filtered tab of an artist's channel is not the artist's page, so it leads nowhere here.
 */
function moreTarget(button: Json): EntityRef | undefined {
  const endpoint = button?.buttonRenderer?.navigationEndpoint;
  const browse = browseRef(endpoint?.browseEndpoint);
  if (browse) return browse.kind === 'ALBUM' || browse.kind === 'PLAYLIST' ? browse : undefined;
  const playlistId: string | undefined = anyWatchEndpoint(endpoint)?.playlistId;
  return playlistId ? playlistRef(playlistId) : undefined;
}

export function carousel(shelf: Json): PageBlockCollection | undefined {
  const header = shelf?.header?.musicCarouselShelfBasicHeaderRenderer;
  const title = runsText(header?.title);
  if (!title) return undefined;
  const context = runsText(header.strapline);
  const id = [context, title].filter(Boolean).join(' / ');
  const contents: Json[] = Array.isArray(shelf.contents) ? shelf.contents : [];
  const isTrackList = contents.length > 0 && contents.every((content) => content?.musicResponsiveListItemRenderer);
  const items: MetadataItem[] = [];
  for (const content of contents) {
    const item = content?.musicTwoRowItemRenderer
      ? card(content.musicTwoRowItemRenderer, id)
      : content?.musicResponsiveListItemRenderer
        ? row(content.musicResponsiveListItemRenderer, id)
        : undefined;
    if (item && !items.some((seen) => sameEntity(seen.entity, item.entity))) items.push(item);
  }
  if (items.length === 0) return undefined;
  const avatar = thumbnailUrl(header.thumbnail);
  const titleTarget = (header.title?.runs ?? []).map((run: Json) => browseRef(run?.navigationEndpoint?.browseEndpoint)).find(Boolean);
  return {
    type: 'collection',
    id,
    header: {
      title,
      context,
      avatar: avatar ? { url: avatar } : undefined,
      target: titleTarget ?? browseRef(header.thumbnail?.musicThumbnailRenderer?.onTap?.browseEndpoint),
    },
    layout: isTrackList ? 'MULTI_COLUMN_LIST' : 'HORIZONTAL_SHELF',
    defaultItemView: isTrackList ? 'TRACK_ROW' : 'COVER_CARD',
    items,
    showAll: moreTarget(header.moreContentButton),
  };
}

export interface TrackTableOptions {
  numbered?: boolean;
  showAll?: EntityRef;
  defaultArtists?: ArtistCredit[];
  album?: { name: string; ref: EntityRef };
  fallbackArtwork?: { url: string };
}

/** The rows of one list shelf, numbered when asked, in the order they are served. */
export function trackTable(id: string, title: string | undefined, contents: Json[], options: TrackTableOptions = {}): PageBlockCollection | undefined {
  const items = rows(contents, id, options);
  if (items.length === 0) return undefined;
  return {
    type: 'collection',
    id,
    header: title ? { title } : null,
    layout: 'TRACK_TABLE',
    defaultItemView: 'TRACK_ROW',
    items,
    showAll: options.showAll,
  };
}

/**
 * Blocks with unique ids, and so unique item ids: two shelves that share a title (the same context
 * and title served twice) keep both, the second under a numbered id.
 */
export function uniqueBlocks(blocks: (PageBlock | undefined)[]): PageBlock[] {
  const seen = new Set<string>();
  const result: PageBlock[] = [];
  for (const block of blocks) {
    if (!block) continue;
    let id = block.id;
    for (let n = 2; seen.has(id); n++) id = `${block.id}#${n}`;
    seen.add(id);
    if (id === block.id) result.push(block);
    else if (block.type === 'collection') result.push({ ...block, id, items: block.items.map((item) => ({ ...item, id: `${id}${item.id.slice(block.id.length)}` })) });
    else result.push({ ...block, id });
  }
  return result;
}
