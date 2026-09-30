# Spotify for Milkbeat

Spotify provides Home, Search, artists, albums, playlists, Liked Songs and library
metadata. Select **Spotify** as the metadata provider and **YouTube Music** as an
audio provider in Settings > Plugins. Milkbeat matches the Spotify track's title,
artists, album and duration through its existing audio-provider chain.

Sign in using Milkbeat's phone login. The plugin keeps the `sp_dc` web session in
the host's encrypted secrets and renews access tokens on demand. Public Search
and collection pages also work anonymously. Set the Home time zone to an IANA
zone, such as `Europe/Amsterdam`, for local time based recommendations.

The phone viewer displays the TV's WebView at a responsive phone viewport and
forwards human touch and keyboard input, including verification challenges.
Frames and input use the existing encrypted pairing channel. Spotify refuses
cross-origin iframe embedding, so its login stays at its real origin in the
TV WebView. The plugin's optional `pageScript` corrects the collapsed height
chain in Spotify's account page; it does not modify other providers.

Pages use the native plugin renderer: ordered shelves, round artist cards,
collection headers and paged track rows. Podcasts and audiobooks are omitted.
Library changes, Spotify playback reporting and Spotify audio are not exposed
by this plugin. The current SDK has no metadata mutation operation.

Requires Milkbeat plugin API 2 (Milkbeat 0.8.0 or later), which provides host
HMAC. Existing API 1 plugins remain supported by the host.

From `plugins/`:

```sh
npm ci
npm run check --workspace spotify
npm test --workspace spotify
LIVE=1 npm test --workspace spotify
npm run pack --workspace spotify
```

The live suite uses anonymous Spotify requests and needs no captured credentials.
Packing uses `MBPLUGIN_KEY` or the existing local first-party signing key. The
result is `spotify/build/spotify.mbplugin`; CI publishes it beside the APK.

For the optional live Android smoke tests, pack both plugins and prepare their
test assets from `plugins/`:

```sh
npm run pack --workspace spotify
npm run pack --workspace youtube-music
mkdir -p spotify/build/android-test-assets
cp spotify/build/spotify.mbplugin spotify/build/android-test-assets/
cp youtube-music/build/youtube-music.mbplugin spotify/build/android-test-assets/
```

From the repository root, build `:app:assembleGithubDebug` and
`:app:assembleGithubDebugAndroidTest`, install the debug APK and test APK on the
TV, and run the specific class:

```sh
adb -s TV_SERIAL shell am instrument -w -e class io.github.aedev.flow.plugin.SpotifyPluginHostTest nl.neerdael.milkbeat.debug.test/io.github.aedev.flow.HiltTestRunner
```

These tests use a separate registry and storage directory, fetch anonymous
metadata, capture a native artist-page preview and read a YouTube audio byte
range. They preserve installed providers and do not start audible playback.

TOTP seeds and current persisted-query hashes are derived from Spotify's public
web client by `.github/workflows/spotify-totp.yml` and published to the orphan
`spotify-data` branch. Both have bundled fallbacks and a six-hour public-data
cache. HMAC is performed by the host's crypto API. The plugin does not execute
downloaded Spotify code or depend on the community TOTP gist.

The implementation follows Meld's Spotify metadata behavior and uses the
Milkbeat SDK throughout. See [validation evidence](../../docs/research/spotify-port-validation-2026-09-30.md)
for fixture sources and live validation limits. Spotify's internal web endpoints
can change independently of its documented developer API.
