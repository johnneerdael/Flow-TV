// Streams: resolving tracks (with their music video) and videos to playable URLs, and the warm-up
// that prepares YouTube's player code off the playback path.
import type { PluginDefinition } from '@milkbeat/plugin-sdk';

export const audio: NonNullable<PluginDefinition['audio']> = {};
export const videoStreams: NonNullable<PluginDefinition['video']> = {};
export const lifecycle: NonNullable<PluginDefinition['lifecycle']> = {};
