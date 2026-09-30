// Entity pages: what the ref points at decides the page. A genre's, artist's or label's tab comes as
// the request's filter, or as a list ref of its own from a Featured shelf's "show all".
import type { MetadataPage, PageRequest } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { pageOf } from '../api/paging';
import { type Tab, TABS, target } from '../pages/refs';
import { librarySection } from './library';
import {
  chartEntity,
  forYouEntity,
  genresEntity,
  newChartsEntity,
  playlistEntity,
  releaseEntity,
  topEntity,
  topReleasesEntity,
} from './lists';
import { ownerPage } from './owners';

function onePage(page: number): void {
  if (page > 1) fail('NOT_FOUND', 'This page has no further pages');
}

export async function entity(request: PageRequest): Promise<MetadataPage> {
  const found = target(request.entity);
  const page = pageOf(request.cursor);
  switch (found.type) {
    case 'release':
      return releaseEntity(found.id, page);
    case 'owner': {
      if (found.tab !== undefined) return ownerPage(found.owner, found.id, found.tab, page, true);
      const filter = request.filterId ?? undefined;
      if (filter !== undefined && !TABS[found.owner].includes(filter as Tab)) fail('NOT_FOUND', `No tab ${filter}`);
      return ownerPage(found.owner, found.id, filter as Tab | undefined, page, false);
    }
    case 'chart':
      return chartEntity(request.entity, found.id, page);
    case 'playlist':
      return playlistEntity(request.entity, found.source, found.id, page);
    case 'top':
      onePage(page);
      return topEntity(request.entity, found.scope, found.scope === 'all' ? undefined : found.id, found.scope !== 'all' && found.hype === true);
    case 'topReleases':
      onePage(page);
      return topReleasesEntity(request.entity, found.genreId);
    case 'newCharts':
      return newChartsEntity(request.entity, page);
    case 'genres':
      onePage(page);
      return genresEntity(request.entity);
    case 'forYou':
      onePage(page);
      return forYouEntity(request.entity);
    case 'library':
      return librarySection(found.section, page);
    case 'track':
      return fail('UNSUPPORTED', 'A Beatport track has no page of its own; its release has');
  }
}
