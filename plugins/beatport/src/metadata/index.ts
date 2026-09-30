// The metadata role: home, search, entity pages, Play all and the library. Beatport has no radio and
// no search suggestions, so neither is offered.
import type { PluginDefinition } from '@milkbeat/plugin-sdk';
import { entity } from './entity';
import { home } from './home';
import { library } from './library';
import { search } from './search';
import { tracks } from './tracks';

export const metadata: NonNullable<PluginDefinition['metadata']> = { home, search, entity, tracks, library };
