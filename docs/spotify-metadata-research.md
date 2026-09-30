# MusicViz metadata plugins: recovered research and revised scope

Date: 2026-09-27. Repository checked at `80b4848b` on `main`.

Historical proposal: the current runtime, playback scope and selected Spotify
account model are documented in [the September 30 validation](research/spotify-port-validation-2026-09-30.md).
The capture observations below remain evidence for the earlier TV account flow.

Status: research and architecture proposal. No plugin implementation has been made.
The supplied full Spotify HAR has now been analyzed with wiretrail. Its
[authentication and metadata report](research/spotify-tv-har-2026-09-27.md)
contains current evidence IDs, response mappings and remaining gaps.

## Conclusion

Build a MusicViz metadata plugin system, with Spotify as its first external
provider. Enabling a provider changes the music catalog experience: account home,
search, artists, albums, playlists, library, saved tracks, and recommendations.
MusicViz renders the returned data using its own native TV components.

Support independently installable and updateable plugins from arbitrary third
parties. The earlier recommendation to compile Spotify into the application does
not meet the clarified goal and is superseded.

Phase 1 is metadata only. Do not change the playback entity, player, audio
resolution, queue persistence, or cache identity. Do not implement an audio plugin
API, Spotify-to-YouTube matching, or playback of plugin tracks in this phase.
Audio providers and playback integration belong to phase 2.

Spotify supplies account metadata, not audio. Its TV web-app requests are the
preferred research path for the Spotify plugin. The new HAR will establish the
request and response contracts; it is evidence for implementation, not a file
that end users must import to use the plugin.

## Latest requirements take precedence

| Decision | Current scope |
| --- | --- |
| Extension model | A real plugin system, not just a Spotify setting compiled into the APK |
| Plugin authors | Arbitrary third parties, explicitly selected by the user |
| Metadata selection | One active metadata provider controls the music browsing experience |
| Content capabilities | Artists, albums and playlists in Search and Library; tracks remain core; podcasts are optional per plugin |
| Initial Spotify configuration | Account music metadata, retaining the earlier music-only default |
| YouTube metadata plugin | No podcasts or episodes in any metadata surface; retain the other agreed content and view types |
| Developer tooling | An SDK and an internal Spotify demo plugin built against the public SDK |
| Authentication | Provider-owned session flow; Spotify TV QR pairing is the researched candidate |
| Presentation | Plugins compose pages from a versioned set of native MusicViz view types |
| Phase 1 playback | Existing playback remains unchanged; external metadata tracks are browse-only |
| Phase 2 | Audio plugins, matching, playback integration, and any required playback identity changes |

The earlier session approved a match-cache Room table, look-ahead matching,
Spotify radio playback, and unavailable-track skipping. Those decisions are
recovered background for phase 2, not authorization to implement them in phase 1.
Radio recommendations can be represented as metadata without starting playback.
Likes can write back to the selected metadata account independently of audio.

## What was recovered from the interrupted session

The supplied PDF, `Session_ Plugin system for metadata providers.pdf`, ends with
the assistant preparing to write the specification. The original local session
continues beyond the PDF and includes explicit requests for a written design,
implementation plan, and a full Markdown research report with a conclusion.
The intended documents were not retained.

Historical tool outputs from session
`3943d5bd-1a30-4bdb-8920-0fd2c227c095` corroborate successful tests on September 27:

| Operation | Historical evidence | Current evidence limit |
| --- | --- | --- |
| TV QR pairing | The user approved the scratch test's own device-code login | No new account login was performed during recovery |
| Home, track search, album metadata/tracks, playlist tracks | Stored tool results report HTTP 200 | Complete payloads need the fresh capture and fixture validation |
| Token refresh | Stored tool results report HTTP 200; the session reports rotating refresh tokens | Concurrent refresh and persistence behavior are not implementation-tested |
| Music-only home | The earlier investigation reports `facet: "music-chip"` excluding non-music content | Recheck response types in the new HAR |
| Track ISRC | Stored results report successful `metadata/4` access | Relevant to later matching; not a phase 1 playback requirement |
| Radio recommendations | Stored results report radio lookup and successful playlist responses | Proves metadata retrieval, not Spotify audio playback |

These are historical observations, not a new end-to-end verification. The old
HARs and scratch response files were absent when checked during recovery. Their
absence must not be replaced with invented fixtures or claims of current tests.

Two unrelated changes from the session already exist on main:
`3e1c1ee8` displays TV playback warnings and opens the player for tracks that fail
to start; `80b4848b` ignores the local debug capture directory.

## Public Spotify TV bundle recheck

During recovery, the unauthenticated
[TV app entry page](https://api-partner.spotify.com/tvapp?platform=androidtv)
and its referenced JavaScript were fetched successfully. The page identified
build `2026.08.26_4-f1b1b7e` and loaded
[spotifytv.js](https://tv.scdn.co/androidtv/v2/f1b1b7e/js/spotifytv.js).

The bundle contains the home operation and its persisted-query hash, a
`curiosity/v1/query` client, and separate `pathfinder/v1/query` uses. This confirms
that public client code can inform operation discovery. It does not establish
that every operation uses the same endpoint or headers.

The version conversion was also located: concatenate the two-digit year,
two-digit month, two-digit day, and build number padded to at least two digits;
then right-pad the result to nine characters. For the observed build this yields
`260826040`. The fresh HAR must confirm where the resulting version is sent.

`curateItem` and `uncurateItem` obtain their hashes through local variables.
An extractor that only finds an operation name immediately followed by a literal
hash would miss them. Bundle extraction needs fixtures and completeness checks;
it must never execute downloaded Spotify code inside the MusicViz host process.

The old cookie/TOTP path was an earlier research branch. It should not displace
the TV pairing path without new evidence. A WebView-based API is a data source;
using its endpoints does not require embedding Spotify's whole interface.

## What to take from Spotube

[Spotube's architecture](https://docs.spotube.cc/developing-plugins/architecture/)
separates host services from plugin services. Its
[plugin manifest](https://docs.spotube.cc/creating-a-plugin/) distinguishes the
capabilities requested from the host from the services a plugin provides.
These are useful boundaries for MusicViz's own contract.

The local Spotify reference binds separate browse, search, track, album, artist,
playlist, user, and account services. This demonstrates that a metadata plugin
can supply the full catalog experience. Its request implementations and data
models are reference material, not code adopted into MusicViz.

The linked [YouTube audio plugin](https://github.com/KRTirtho/spotube-plugin-youtube-audio)
uses `.ht` sources and a different manifest/API generation from the local
Kotlin/JS Spotify project. Treat it as a conceptual example, not a package proven
compatible with the newer system. Its matching and stream-resolution methods
belong entirely outside MusicViz phase 1.

## Runtime choice for arbitrary third-party code

Spotube's documentation describes its runtime as sandboxed, but
[Zipline's own documentation](https://github.com/cashapp/zipline#secure) explicitly
says it provides neither a sandbox nor process isolation and is intended for
trusted code. Signature verification identifies a publisher; it does not make
arbitrary publisher code trustworthy. Consequently, in-process Zipline alone
does not satisfy the selected third-party-plugin requirement.

| Candidate | Fit | Work required before selection |
| --- | --- | --- |
| AndroidX JavaScriptEngine, with host-mediated services | Downloadable script packages and a platform-provided process boundary | Verify support on the target TVs, required termination/resource features, request bridging, and credential separation |
| Separate plugin APKs exposing a narrow bound service | Native provider implementation with Android application isolation | Specify IPC contract, caller/provider validation, installation UX, pagination limits, and process-death handling |
| In-process native loading or Zipline alone | Insufficient isolation for arbitrary plugin code | Do not select as the phase 1 execution boundary |

[AndroidX JavaScriptEngine](https://developer.android.com/develop/ui/views/layout/webapps/jsengine)
runs outside the application process and requires both API 26+ and a supporting
WebView implementation. Android describes separate isolates within one sandbox
as weak security boundaries; they must not be assumed to protect different
plugins' credentials from each other. Availability and failure behavior must be
tested before choosing it for TV deployment.

[Android's application sandbox](https://source.android.com/docs/security/app-sandbox)
and [bound services](https://developer.android.com/develop/background-work/services/bound-services)
provide the platform alternative. This avoids inventing an isolation mechanism,
at the cost of installing a separate APK for each provider.

Recommendation: keep the metadata contract independent of its transport. Evaluate
the platform JavaScript sandbox first if in-app plugin-package installation is
the desired experience; select the APK service model if target-device support or
the required isolation is insufficient. Implement one selected transport, not
two speculative loaders. Runtime selection remains open pending that check.

## Proposed phase 1 boundaries

### Plugin host

Own installation, compatibility checks, enable/disable state, update/rollback,
session lifecycle, and failure reporting. Validate plugin IDs and schema versions.
Keep one active metadata provider, with YouTube Music as the built-in default.
Switching provider cancels obsolete metadata requests and clears the old
provider's visible account data; it does not modify the current playback queue.

Give each plugin only the host services it needs. For a script runtime, broker
HTTP, plugin-scoped storage, and authentication presentation through explicit
permissions. Validate network destinations, redirects, payload sizes, and
timeouts at that boundary. Never expose arbitrary Android objects, app database
handles, player commands, another provider's credentials, or host filesystem
access. An APK transport must enforce equivalent separation through its IPC
contract and Android ownership rather than pretending a host HTTP broker can
control an independent application's own network permission.

### Metadata contract

Represent tracks, artists, albums, playlists, optional podcast metadata, page
blocks, account state, and opaque pagination cursors with provider-neutral metadata types. A reference includes
the plugin ID, entity type, and the provider's opaque identifier. These are new
catalog types; they do not alter or replace the existing playback entity.

Expose home, search, suggestions where supported, collection details, artists,
library, saved-state reads/writes, and recommendation lists. Declare supported
features so the UI can omit unavailable actions. Account caches must be keyed by
plugin and account, invalidated on logout/account change, and refreshed on demand.

### Music UI

The user's Spotify Home, YouTube Music Library, and YouTube Music Explore
screenshots establish that the plugin needs a page-composition API. Returning a
flat catalog or forcing every response into the same shelf is insufficient.
Plugins choose supported view types and compose sections in provider order.
MusicViz renders those descriptions using native components.

MusicViz owns focus, navigation execution, accessibility, loading/error states,
theme, and performance. Plugins supply content, available filters/sorts, section
order, view types, and semantic navigation targets. Do not introduce
plugin-supplied Compose code, HTML pages, or arbitrary executable UI.

Home, music search, artist/album/playlist pages, and account library must all use
the active provider. Content filtering follows the plugin's declared kinds;
the initial Spotify music-only configuration still filters non-music types.
Never display YouTube results as if they came from Spotify.

For external metadata tracks in phase 1, do not expose Play, Queue, or Download
actions that cannot work. Existing YouTube playback and its controls remain
host-owned. No Spotify placeholder may enter a `videoId`, media item, history
writer, playback queue, or stream resolver.

The detailed, current page/block/element schema and screenshot coverage live in
[Metadata plugin UI contract](metadata-plugin-ui-contract.md). That contract
includes banners, cover/portrait/landscape cards, track tables, compact lists,
section-local filters, editorial features, biographies and optional event rows.
Library and Search require artist, album and playlist support; podcast shows and
episodes are optional declared capabilities. This supersedes the earlier blanket
music-only restriction at the framework level.

The [SDK and validation design](metadata-plugin-sdk-design.md) defines the
phase 1 developer deliverables and the internal Spotify demo. It is separate
from a future task-by-task implementation plan.

### Lifecycle and failure behavior

Load plugins lazily when selected or needed for login. Cancel work when its
surface is left or provider changes. Use demand-driven paging and bounded HTTP
concurrency. Keep plugin code off the UI thread and out of frame-clock work.
Handle timeout, malformed data, process death, logout, and disabled/removed
plugins with native UI states. A metadata plugin failure must not issue player
commands or restart existing audio.

## Current-code findings

- `MusicHomeFeedViewModel.kt` exposes `HomePage.Chip` and directly selects between
  account and anonymous YouTube calls. Its feed state needs a metadata-facing
  adapter; playback need not change.
- `TvSearchScreen.kt` uses `MusicSearchViewModel`, whose results include `YTItem`.
  A provider-neutral TV metadata search path must cover both results and
  suggestions without altering the phone's existing search behavior incidentally.
- `TvMusicCollectionScreen.kt` and `TvArtistScreen.kt` depend on the shared
  `MusicViewModel`; their metadata reads need explicit provider routing.
- `TvLibraryScreen.kt` contains both account and local sections. The remote
  account portion switches providers; local history/download behavior is not
  migrated into a plugin as incidental work.
- `MusicTrack.videoId`, `EnhancedMusicPlayerManager.buildMediaItem`, and the
  player ViewModel's download/history paths are coupled to YouTube identity.
  This is a reason to keep the new catalog types outside those paths in phase 1,
  not a reason to perform a playback refactor now.
- The existing lyrics registry is a registry of built-in objects. It does not
  provide package installation, external execution, or isolation.

The graph was queried for navigation, then findings were checked against current
source because its timestamp predates the latest commit. No source code changed.

## Next evidence and acceptance criteria

The first HAR verifies the device-login sequence, all 21 home sections, filtered
library reads, artist/album/playlist responses and saved-state reads. A long
playlist is captured through its last page. The
[04:43 follow-up](research/spotify-tv-har-2026-09-28-0443.md) validates browse and
save/unsave request paths. Search results and a live refresh exchange remain
unverified. The reports record entry IDs, response roots, paging and the
saved-status request-growth failure. Keep credentials and raw account payloads
out of reports and fixtures.

Before finalizing the runtime, check target-TV support and prove that a malformed
or non-terminating test plugin can be stopped without accessing MusicViz data or
interrupting playback. Do not make performance claims until measured on-device.

Phase 1 is complete when the SDK can build and validate an independently installed
Spotify demo plugin that authenticates, supplies the required browsing surfaces,
can be updated/disabled, and fails cleanly. Validate it with sanitized HAR fixtures
and on-device checks. Existing playback behavior and entities remain unchanged.
The HAR findings and runtime checks feed the phase 1 specification and implementation
plan. Phase 2 playback work remains explicitly deferred.
