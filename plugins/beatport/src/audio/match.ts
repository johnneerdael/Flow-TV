import type { AudioMatches, MatchAudioRequest } from '@milkbeat/plugin-sdk';
import { search } from '../api/catalog';
import { trackDescriptor } from '../pages/tracks';

const MAX_CANDIDATES = 25;

export async function matchAudio({ track }: MatchAudioRequest): Promise<AudioMatches> {
  const query = [track.artists?.[0]?.name, track.title].map((value) => value?.trim()).filter(Boolean).join(' ');
  if (!query) return { candidates: [] };
  const results = await search(query, { type: 'tracks', perPage: MAX_CANDIDATES });
  return {
    candidates: (results.tracks ?? [])
      .filter((candidate) => Number.isInteger(candidate.id) && candidate.id > 0 && candidate.name?.trim() && candidate.is_available_for_streaming !== false)
      .slice(0, MAX_CANDIDATES)
      .map(trackDescriptor),
  };
}
