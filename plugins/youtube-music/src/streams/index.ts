// Streams: resolving tracks (with their music video) and videos to playable URLs, and the warm-up
// that prepares YouTube's player code off the playback path.
import type { PluginDefinition } from '@milkbeat/plugin-sdk';
import { mb } from '@milkbeat/plugin-sdk';
import { matchAudio } from '../music/match';
import { resolveAudio } from './audio';
import { prewarmPoTokens } from './potoken/session';
import { warmUpSolvers } from './solver';
import { reportPlayback } from './tracking';
import { resolveVideo } from './video';
import { tokenVisitor } from './visitor';

export const audio: NonNullable<PluginDefinition['audio']> = {
  resolve: resolveAudio,
  match: matchAudio,
  reportPlayback,
};

export const videoStreams: NonNullable<PluginDefinition['video']> = {
  resolve: resolveVideo,
};

export const lifecycle: NonNullable<PluginDefinition['lifecycle']> = {
  async warmUp() {
    try {
      await warmUpSolvers();
    } catch (error) {
      await mb.log.write({ level: 'WARN', message: `Solver warm-up failed: ${error instanceof Error ? error.message : String(error)}` });
    }
    await prewarmPoTokens(await tokenVisitor());
  },
};
