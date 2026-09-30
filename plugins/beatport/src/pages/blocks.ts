// The few block shapes the pages are built from. A block with no items is dropped, so a page never
// shows an empty shelf.
import type { CollectionHeader, EntityRef, MetadataItem, PageBlock, PageBlockCollection } from '@milkbeat/plugin-sdk';

export interface BlockOptions {
  showAll?: EntityRef;
  showAllFilterId?: string;
  context?: string;
  target?: EntityRef;
}

function collection(
  id: string,
  title: string | undefined,
  layout: PageBlockCollection['layout'],
  defaultItemView: PageBlockCollection['defaultItemView'],
  items: MetadataItem[],
  options: BlockOptions,
): PageBlockCollection | undefined {
  if (items.length === 0) return undefined;
  const header: CollectionHeader | null = title ? { title, context: options.context, target: options.target } : null;
  return { type: 'collection', id, header, layout, defaultItemView, items, showAll: options.showAll, showAllFilterId: options.showAllFilterId };
}

/** A row of cards: releases, charts, playlists, artists or genres. */
export function shelf(id: string, title: string, items: MetadataItem[], options: BlockOptions = {}): PageBlockCollection | undefined {
  const view = items[0]?.view === 'ARTIST_PORTRAIT' ? 'ARTIST_PORTRAIT' : 'COVER_CARD';
  return collection(id, title, 'HORIZONTAL_SHELF', view, items, options);
}

/** Tracks, numbered or not, one per row; also every full list, as the host has no grid. */
export function table(id: string, title: string | undefined, items: MetadataItem[], options: BlockOptions = {}): PageBlockCollection | undefined {
  return collection(id, title, 'TRACK_TABLE', 'TRACK_ROW', items, options);
}

/** A few tracks in compact columns, as a home feed's picks. */
export function columns(id: string, title: string, items: MetadataItem[], options: BlockOptions = {}): PageBlockCollection | undefined {
  return collection(id, title, 'MULTI_COLUMN_LIST', 'TRACK_ROW', items, options);
}

export function present(blocks: (PageBlock | undefined)[]): PageBlock[] {
  return blocks.filter((block): block is PageBlock => block !== undefined);
}
