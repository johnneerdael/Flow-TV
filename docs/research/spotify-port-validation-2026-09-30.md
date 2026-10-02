# Spotify port: research validation

Date: 2026-09-30. Source branch: `spotify`, based on current `origin/main` plus
the four existing matching/TOTP commits. No account credential from a capture
was replayed. Local captures and public bundle downloads remain under ignored
`debug/spotify-port/`.

## What changed since the older research

The September 27 metadata SDK and runtime proposals describe an earlier design.
The current host implements signed JavaScript `.mbplugin` packages, QuickJS,
native page rendering and web-login methods. The four branch commits also add
host HMAC, audio matching, YouTube Music matching and playback delegation across
providers. A separate APK runtime and a browse-only Spotify phase are superseded
by that implementation.

The selected account model for this port is Meld's web-cookie flow, using
`sp_dc`, server time and TOTP. The TV device-code flow in the older HAR reports
is a different account model. Its refresh token must never be supplied to the
web-cookie renewal endpoint.

## Supplied flows and desktop screenshots

Converted `/Users/jneerdael/Downloads/spotify-flows` with mitmproxy's HAR exporter
without replaying requests. Wiretrail reports 907 entries over 109.2 seconds.
The recording also contains traffic from other applications; its overall errors,
duplicate calls and generic authentication heuristics are not Milkbeat results.

The recording has 16 Pathfinder v2 requests, 14 with named operations. All 14
named operations returned HTTP 200. The metadata evidence includes:

| Operation | Capture entry | Observation |
| --- | --- | --- |
| `home` | `e000220` | 31 ordered sections, mixed entity wrappers, music/podcast/audiobook chips, section-local counts and next offsets |
| `queryArtistOverview` | `e000794` | Artist profile, portrait/header artwork, monthly listeners, popular tracks, releases and related artists/playlists |
| `lookupChildEntities`, `feedBaselineLookup` | Multiple entries | Supplemental desktop metadata operations; not required by this port |

The capture contains no web `/api/token` request, Search response, album request
or playlist request. It cannot establish this plugin's sign-in or renewal. The
four supplied screenshots establish shelf order and the artist/playlist content
hierarchy; they do not establish API endpoints. Milkbeat uses its existing
portrait and cover-pane headers rather than copying desktop decoration.

Sanitized fixtures retain the response wrappers and metadata fields used by the
mapper. Credentials, response extensions, tracking fields, extracted colors,
share/session IDs and personal owner names were removed. Account/session tests
and the library response use synthetic data, explicitly separate from capture
evidence.

## Public client and anonymous live checks

Fetched the current [Spotify web client](https://open.spotify.com/) and its
`web-player.e3655ce0.js` bundle. Its query definitions contain the current Home,
artist, album, playlist, library and profile hashes. Home and artist hashes agree
with the supplied capture and differ from Meld's hardcoded defaults.

The web client currently does not publish `searchDesktop` in the inspected main
bundle or its 157 enumerated chunks. Meld's existing Search hash was tested
independently and remains accepted. The registry extractor preserves that
verified fallback rather than inventing a replacement.

The existing TOTP extractor derives versions 59, 60 and 61. Version 61 produced
an anonymous HTTP 200 token response. Publication now includes raw `keyHex`
alongside the existing Base32 `s`, so the plugin uses `mb.crypto.hmac` and needs
no Base32 or cryptographic implementation. Extraction failure prevents the
workflow from overwriting published data.

Anonymous requests verified these operations through the implemented plugin:

| Surface | Result |
| --- | --- |
| Search | Music track results with credits and durations |
| Artist `19:26` | Header, popular tracks, releases and related artists |
| Album `Prophecy` | Tracks inherit album identity and cover artwork |
| Public playlist | Paged track descriptors; playlist `trackDuration` mapping |
| Individual track `Prophecy` | Album plus `firstArtist`/`otherArtists` credits |

An additional live handoff passed the Spotify `Prophecy` descriptor to YouTube
Music's `audio.match`. The first returned candidate was `yjGGtXHMc6g`, titled
`Prophecy`, with credits Anyma, 19:26 and Baset and duration 143 seconds, against
Spotify's 142615 ms. The Android smoke test additionally installed both signed
packages into an isolated registry, matched the Spotify descriptor, resolved the
YouTube stream and successfully read a 1024-byte range of its audio response.
This verifies stream access on the TV; audible playback was not started.

The `homeSection` operation returns `data.homeSections.sections`, not
`data.homeSection`. Missing `uri` returns HTTP 400 with the required GraphQL
type `String!`; the alternative `uris`, `sectionUris` and `sectionUri` fields
do not satisfy it. An anonymous request for a captured personalized section
returned `NotFound`; the provider handles that state. Successful signed-in
section expansion and pagination remain unverified. Anonymous Home returned no
sections, so Home and Library require a signed-in account.

## Verification and remaining device work

- Plugin TypeScript checks and the SDK generation consistency check passed.
- The complete YouTube Music, Beatport and Spotify offline plugin suites passed.
- Spotify's 17 offline cases and anonymous live case passed.
- All 11 public-data extraction tests passed, including RFC 6238 vectors.
- `:plugin-api:test` and the targeted Github debug package, playback resolver
  and matching unit tests passed.
- `ktlintCheck`, Github debug APK/test builds and Foss debug compilation passed.
- Three Android tests passed on the API 28 TV: native QuickJS/HMAC catalog reads,
  artist-page rendering and readable YouTube audio for a Spotify track.
- The package was built and signed with the existing signing author key.

The native render test captures the Compose root into
`debug/spotify-port/spotify-artist-tv.png`. The screenshot was visually inspected:
the artist name, portrait, biography, native Play/Shuffle actions and numbered
track rows render with Milkbeat's theme. It is a renderer test rather than a
test of route navigation and focus restoration.

Offline cases cover token-renewal deduplication, logout and account-switch races,
transient failure preservation, rejected-token retry, query-hash rotation,
request deduplication, pagination scope, duplicate playlist occurrences and
non-music filtering. The plugin has no timer or frame-clock work.

The subsequent phone-viewer run passed two Android tests on the same API 28
TV: encrypted hardware screenshots, trusted touch and native typing inside a
sandboxed iframe, hidden-view capture gating, and the real Spotify page at a
360 dp viewport. Release APKs 0.7.1 and 0.7.2 were installed as updates using
the existing certificate and retained the application UID and existing encrypted
account files. On September 30 the user confirmed successful Spotify sign-in
through the phone viewer, including the human verification challenge.

Still requiring a signed-in device run: personalized Home and section
expansion, real account library and Liked Songs, token renewal with the account,
D-pad focus/scroll restoration and audible Spotify-to-YouTube playback.
No performance, battery or thermal improvement is claimed.


## 0.8.0 release verification

The release review added regressions for Unicode matching, persisted match
success/misses and cancellation, unavailable-recording recovery, reporting and
radio seed continuation. Scoped candidate exclusions do not become cached
catalog misses. Spotify requires API 2 so older hosts without HMAC reject the
package; existing API 1 packages remain supported.

Provider data rejects malformed cache shapes and unusable TOTP versions.
Registry requests have two-second deadlines to leave time for bundled fallback
within the host call budget. Phone-script tests cover typed request-bound frame
replies, visibility changes, stalled-request recovery and blob cleanup.

The complete Github unit suite, Foss compilation, Github debug/test builds,
workspace checks/offline suites, eleven extraction tests and four phone-script
tests passed after review fixes. Release formatting, lint and the 0.8.0 build
passed. Baseline and startup profiles were regenerated using the managed
emulator; both generator journeys passed (three unrelated benchmark tests were
skipped by the generator run). Profile size is not a performance measurement.

Five native tests passed on the API 28 TV after waking its sleeping display.
The audio test now calls `PluginAudio.resolve` with the production matcher and
an isolated Room cache, rather than selecting the first raw match candidate.
The two remote-view tests cover the real Spotify viewport and trusted iframe
input. The signed APK certificate matches the existing release certificate.

The owner approved deferring the existing oversized music-service split for
0.8.0 and confirmed this interface becomes the default for plugin sign-in only.
