// Beatport's editorial page modules. Only some accounts' tokens may read them (the docs browsing
// token may not), so a refusal means "no modules", never a failed page.
import type { PluginErrorCode } from '@milkbeat/plugin-sdk';
import { get } from './client';
import type { Paginated, PageModule } from './types';

const REFUSED: PluginErrorCode[] = ['UNAVAILABLE', 'NOT_FOUND'];

/** A genre page's modules with their items, or none when this account may not read them. */
export async function genreModules(genreId: string): Promise<PageModule[]> {
  try {
    const page = await get<Paginated<PageModule>>('curation/page-modules/', { genre_id: genreId, items: true });
    return page.results ?? [];
  } catch (error) {
    if (REFUSED.includes((error as { code?: PluginErrorCode }).code as PluginErrorCode)) return [];
    throw error;
  }
}
