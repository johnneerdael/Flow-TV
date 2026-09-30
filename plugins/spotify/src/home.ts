import { fail, mb } from '@milkbeat/plugin-sdk';
import type { HomeRequest, MetadataPage, PageRequest } from '@milkbeat/plugin-sdk';
import { requireAccount } from './account';
import { query } from './api';
import { required } from './catalog';
import { cards } from './mapping';
import { collection, present } from './pages';
import { array, at, items, label, nextCursor, object, offset, text } from './util';

async function variables(facet = 'music-chip') {
  const settings = await mb.settings.get();
  return { homeEndUserIntegration: 'INTEGRATION_WEB_PLAYER', timeZone: settings.find((s) => s.key === 'timeZone')?.value || 'UTC', sp_t: '', facet, sectionItemsLimit: 10, includeEpisodeContentRatingsV2: false };
}

export async function home(request: HomeRequest): Promise<MetadataPage> {
  await requireAccount();
  if (request.cursor) fail('UNSUPPORTED', 'Spotify Home has no page cursor');
  const facet = request.filterId ?? 'music-chip';
  if (!['music-chip', 'music-following-chip'].includes(facet)) fail('UNSUPPORTED', 'Unsupported Spotify Home filter');
  const data = required(at(await query('home', await variables(facet)), 'home'));
  const sections = items(at(data, 'sectionContainer', 'sections'));
  const blocks = sections.map((value, index) => {
    const section = object(value);
    const id = text(section.uri) ?? `section-${index}`;
    const page = object(section.sectionItems);
    const values = cards(items(page));
    const title = label(at(section, 'data', 'title')) ?? (at(section, 'data', '__typename') === 'HomeShortsSectionData' ? 'Jump back in' : undefined);
    return collection(id, title, values, false, text(section.uri) && Number(page.totalCount) > items(page).length ? { kind: 'MIX', providerId: id } : undefined);
  });
  return { id: 'spotify/home', blocks: present(blocks), filters: { options: [{ id: 'music-chip', label: 'Music' }, { id: 'music-following-chip', label: 'Following' }] } };
}

export async function homeSection(request: PageRequest): Promise<MetadataPage> {
  await requireAccount();
  const id = request.entity.providerId;
  if (!/^spotify:section:[A-Za-z0-9]{22}$/.test(id)) fail('UNSUPPORTED', 'Invalid Spotify Home section');
  const start = offset(request.cursor, id);
  const result = await query('homeSection', { ...await variables(), uri: id, offset: start, limit: 50 });
  const section = required(array(at(result, 'homeSections', 'sections'))[0]);
  const page = object(section.sectionItems);
  return { id: `spotify/${id}`, blocks: present([collection(id, label(at(section, 'data', 'title')), cards(items(page)))]), nextCursor: nextCursor(id, start, items(page).length, page.totalCount) };
}
