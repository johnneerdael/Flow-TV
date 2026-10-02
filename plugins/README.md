# Plugin SDK and published packages

The public workspace contains the SDK, packaging CLI, signed test fixture and compiled first-party packages. Beatport, Spotify and YouTube Music sources, tests, generation and signing live in the private `johnneerdael/Milkbeat-Plugins` repository. Their original IDs and author key remain unchanged.

```sh
npm ci
npm run check
npm test
node scripts/stage-published-plugins.mjs
```

The staging command checks every compiled package against `published.json`, the pinned author and its code in the encrypted app catalog, then prepares `dist/` for the Android release. Public CI needs no plugin signing secret or Buzzheavier browser session.

## Promote a private publication

Run the private publisher in Actions, verify its native downloads, and download its successful `publication-metadata` artifact. Copy `latest.json` to `plugins/published.json`, `catalog.json` to `app/src/main/assets/plugin-download-catalog.json`, and the canonical `packages/*.mbplugin` files to `plugins/packages/`. Copy `latest.json` to `app/src/androidTest/assets/published-plugins.json` for the native promotion check.

Run the checks above and review those files together. Preserve old catalog assignments. Canonical packages come from publication metadata, because signing the same content again can produce a different archive hash. The next Android release includes new codes and attaches the verified packages.

The public Spotify provider-data workflow and its `spotify-data` URLs remain available; its search hash fallback lives in `.github/data/spotify-hashes.json`.
