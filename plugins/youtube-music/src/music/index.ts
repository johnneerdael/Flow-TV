// The metadata role: YouTube Music's home, search, artist/album/playlist pages, library and radio.
// The signed-in listener's own home and pages when there is an account, the anonymous ones otherwise.
import type { HomeRequest, MetadataPage, PluginDefinition, SearchRequest, SuggestRequest, Suggestions } from '@milkbeat/plugin-sdk';
import { type Json, dig } from '../util';
import { browse, search as searchRequest, signedIn, suggestions, verifyAccount } from './api';
import { entity } from './entity';
import { homeContinuationPage, homePage } from './home';
import { library } from './library';
import { radio } from './radio';
import { searchContinuationPage, searchFilteredPage, searchSummaryPage } from './search';
import { tracks } from './tracks';

async function home(request: HomeRequest): Promise<MetadataPage> {
  // Chip params select the first page only; continuations already carry them.
  const account = await signedIn();
  if (account) await verifyAccount();
  if (request.cursor) return homeContinuationPage(await browse({ continuation: request.cursor }, account));
  return homePage(await browse({ browseId: 'FEmusic_home', params: request.filterId ?? undefined }, account));
}

async function search(request: SearchRequest): Promise<MetadataPage> {
  if (request.cursor) return searchContinuationPage(await searchRequest({ continuation: request.cursor }));
  if (request.filterId) return searchFilteredPage(await searchRequest({ query: request.query, params: request.filterId }));
  return searchSummaryPage(await searchRequest({ query: request.query }));
}

async function suggest(request: SuggestRequest): Promise<Suggestions> {
  const response = await suggestions(request.query);
  const contents: Json[] = dig(response, 'contents', 0, 'searchSuggestionsSectionRenderer', 'contents') ?? [];
  const queries = contents
    .map((content) => content?.searchSuggestionRenderer?.suggestion?.runs)
    .filter(Array.isArray)
    .map((runs: Json[]) => runs.map((run) => run?.text ?? '').join(''));
  return { queries };
}

export const metadata: NonNullable<PluginDefinition['metadata']> = { home, search, suggest, entity, tracks, library, radio };
