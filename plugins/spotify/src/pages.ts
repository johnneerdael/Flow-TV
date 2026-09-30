import type { EntityRef, MetadataItem, PageBlock, PageBlockCollection } from '@milkbeat/plugin-sdk';
import { cards, trackDescriptor, trackItem } from './mapping';

export function collection(id: string, title: string | undefined, values: MetadataItem[], table = false, showAll?: EntityRef): PageBlockCollection | undefined {
  if (!values.length) return undefined;
  return {
    type: 'collection', id, header: title ? { title } : null,
    layout: table ? 'TRACK_TABLE' : 'HORIZONTAL_SHELF',
    defaultItemView: table ? 'TRACK_ROW' : 'COVER_CARD', items: values, showAll,
  };
}

export const shelf = (id: string, title: string, values: unknown[]) => collection(id, title, cards(values));
export const present = (values: (PageBlock | undefined)[]): PageBlock[] => values.filter((v): v is PageBlock => v !== undefined);
export const trackRows = (values: unknown[], start = 0, album?: unknown): MetadataItem[] => values.flatMap((value, index) => {
  const track = trackDescriptor(value, album);
  return track ? [trackItem(track, start + index + 1)] : [];
});
