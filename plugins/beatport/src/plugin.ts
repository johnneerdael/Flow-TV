// Beatport for Milkbeat: the catalog's pages, the listener's library and full-length streams.
import { definePlugin } from '@milkbeat/plugin-sdk';
import { signIn } from './account';
import { resetCatalogCaches } from './api/catalog';
import { audio } from './audio';
import { metadata } from './metadata';

definePlugin({
  lifecycle: {
    async settingsChanged() {
      resetCatalogCaches();
    },
  },
  metadata,
  audio,
  signIn,
});
