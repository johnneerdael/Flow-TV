// Beatport's editorial page modules, as the web store lays out a genre's page: its banners, hype
// picks, staff picks and charts. A genre has a curated page per Beatport surface; the web store's
// is the one whose source is `sushi`.
import type { PluginErrorCode } from '@milkbeat/plugin-sdk';
import { get } from './client';
import type { CurationPage, PageModule, PageModuleItem, Paginated } from './types';

const REFUSED: PluginErrorCode[] = ['UNAVAILABLE', 'NOT_FOUND'];
const WEB_STORE = 'sushi';
const MODULE_ITEMS = 50;

async function webStorePage(genreId: string): Promise<CurationPage | undefined> {
  const pages = await get<Paginated<CurationPage>>('curation/pages/', { item_id: genreId, per_page: 10 });
  return (pages.results ?? []).find((page) => page.type?.name === 'genre' && page.source_type?.name === WEB_STORE);
}

/** A genre page's modules in the store's order, with their items; none when this account may not read them. */
export async function genreModules(genreId: string): Promise<PageModule[]> {
  try {
    const page = await webStorePage(genreId);
    if (!page) return [];
    const modules = await get<Paginated<PageModule>>('curation/page-modules/', { page_id: page.id, per_page: 30 });
    const enabled = (modules.results ?? []).filter((module) => module.enabled !== false);
    return await Promise.all(
      enabled.map(async (module) => {
        const items = await get<Paginated<PageModuleItem>>(`curation/page-modules/${module.id}/items/`, { per_page: MODULE_ITEMS });
        return { ...module, items: items.results ?? [] };
      }),
    );
  } catch (error) {
    if (REFUSED.includes((error as { code?: PluginErrorCode }).code as PluginErrorCode)) return [];
    throw error;
  }
}
