// A release as the web store shows it: the cover with every artist, the release date, the label and
// the catalogue number, then its numbered tracks and more from its label. The header credits one
// entity, the first artist; the label is linked from its shelf's title.
import type { MetadataPage, PageBlockHeader } from '@milkbeat/plugin-sdk';
import type { Release, Track } from '../api/types';
import { counted, isPresent } from '../util';
import { LARGE, artwork } from './artwork';
import { TRACKS_BLOCK_ID } from './collections';
import { present, shelf, table } from './blocks';
import { bpmRange, releaseItem } from './items';
import { refs } from './refs';
import { releaseDate, trackItems } from './tracks';

const pageId = (id: number | string) => `beatport/release/${id}`;

function header(release: Release): PageBlockHeader {
  const entity = refs.release(release.id);
  const artists = (release.artists ?? []).filter((artist) => artist.name);
  const first = artists[0];
  return {
    type: 'header',
    id: 'header',
    style: 'COVER',
    entity,
    title: release.name ?? '',
    artwork: artwork(release.image, LARGE),
    attribution: first ? { name: artists.map((artist) => artist.name).join(', '), avatar: artwork(first.image, LARGE), entity: refs.artist(first.id) } : undefined,
    details: [
      releaseDate(release),
      release.label?.name,
      release.catalog_number ?? undefined,
      bpmRange(release.bpm_range),
      release.track_count ? counted(release.track_count, 'track') : undefined,
    ].filter(isPresent),
    description: release.desc ?? undefined,
    tracks: entity,
  };
}

/** The release's first page: header, tracks numbered from one, and the label's other releases. */
export function releasePage(release: Release, tracks: Track[], moreFromLabel: Release[], nextCursor: string | undefined): MetadataPage {
  const label = release.label;
  const others = moreFromLabel.filter((other) => other.id !== release.id).map((other) => releaseItem(other, 'more-from-label'));
  return {
    id: pageId(release.id),
    blocks: present([
      header(release),
      table(TRACKS_BLOCK_ID, undefined, trackItems(tracks, TRACKS_BLOCK_ID, 1)),
      label ? shelf('more-from-label', 'More From This Label', others, { context: label.name, target: refs.label(label.id), showAll: refs.list('label', label.id, 'releases') }) : undefined,
    ]),
    nextCursor,
  };
}

/** A further page of a long release's tracks, numbered on from [firstOrdinal]. */
export function releaseTracksPage(id: string, tracks: Track[], firstOrdinal: number, nextCursor: string | undefined): MetadataPage {
  return { id: pageId(id), blocks: present([table(TRACKS_BLOCK_ID, undefined, trackItems(tracks, TRACKS_BLOCK_ID, firstOrdinal))]), nextCursor };
}

