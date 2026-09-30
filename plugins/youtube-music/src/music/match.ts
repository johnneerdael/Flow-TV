// Finding YouTube Music's own recording of a track another plugin describes (a Spotify track): one
// anonymous song search for its first artist and title, as the app searched. The host scores what
// comes back, so this only offers YouTube's songs in its own order.
import type { AudioMatches, MatchAudioRequest, TrackDescriptor } from '@milkbeat/plugin-sdk';
import { search } from './api';
import { SEARCH_FILTERS, searchFilteredPage } from './search';

const SONGS = SEARCH_FILTERS.find((filter) => filter.kind === 'songs')?.id as string;
const MAX_CANDIDATES = 10;

export async function matchAudio(request: MatchAudioRequest): Promise<AudioMatches> {
  const { track } = request;
  const query = [track.artists?.[0]?.name, track.title].filter((part) => part && part.trim()).join(' ');
  if (!query) return { candidates: [] };
  const page = searchFilteredPage(await search({ query, params: SONGS }));
  const candidates = page.blocks
    .flatMap((block) => (block.type === 'collection' ? block.items : []))
    .map((item) => item.track)
    .filter((candidate): candidate is TrackDescriptor => candidate != null && candidate.ids?.ytm !== undefined)
    .slice(0, MAX_CANDIDATES);
  return { candidates };
}
