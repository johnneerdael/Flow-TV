// Beatport for Milkbeat: the catalog's pages and the listener's library. The audio role (`beatport`
// ids) is declared for the streams that come later; until then the host finds no resolver here.
import { definePlugin } from '@milkbeat/plugin-sdk';
import { signIn } from './account';
import { resetCatalogCaches } from './api/catalog';
import { metadata } from './metadata';

definePlugin({
  lifecycle: {
    async settingsChanged() {
      resetCatalogCaches();
    },
  },
  metadata,
  signIn,
});
