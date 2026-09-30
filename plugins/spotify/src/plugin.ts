import { definePlugin } from '@milkbeat/plugin-sdk';
import { signIn } from './account';
import { resetCatalog } from './api';
import { entity, tracks } from './entity';
import { home } from './home';
import { library } from './library';
import { search } from './search';

definePlugin({
  lifecycle: { async settingsChanged() { resetCatalog(); } },
  signIn,
  metadata: { home, search, entity, tracks, library },
});
