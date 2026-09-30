# Spotify TV capture: authentication and metadata findings

Source: `HTTPToolkit_2026-09-27_21-49.har`, supplied locally by the user.
Analyzed with wiretrail 0.3.1 using default redaction and allowlisted output.
This report contains request contracts, schema names, counts and evidence IDs;
it contains no account credentials or raw account payloads.

## Conclusion

The capture establishes a concrete authentication and metadata-read contract for
the internal Spotify demo: device-code login, authenticated account lookup, home,
library, artist, album, playlist and saved-state reads. It is enough to ground
the first SDK integration and its offline tests in real response structures.

It does not validate an implemented MusicViz plugin. No captured credentials
were replayed, no new Spotify account actions were performed, and no plugin or
playback code was changed during analysis.

Search, save/remove mutations, refresh-token exchange, and some desktop artist
fields still lack evidence in this particular capture. Keep them explicit
validation items rather than calling the complete metadata provider finished.

The host SDK remains capable of optional podcasts. The YouTube metadata plugin
must not expose podcasts or episodes anywhere. The earlier Spotify demo default
remains music-only; this unfiltered capture provides useful filtering fixtures.

## Capture coverage

| Measure | Observation |
| --- | --- |
| File size | 46,718,916 bytes |
| Duration | 219.416 seconds |
| Requests | 1,052 |
| Timing coverage | 100% |
| Response-body coverage | Approximately 97.9% |
| POST request-body coverage | Approximately 99.1% |
| HTTP outcomes | 1,044 2xx; seven 4xx; one protocol upgrade |
| Credentials | Authorization data present; capture is not sanitized |
| Main metadata transport | GET `api-partner.spotify.com/curiosity/v1/query` |
| Additional account lookup | GET `api-partner.spotify.com/pathfinder/v1/query` |

The seven 4xx responses are four expected device-login pending responses, two
saved-status failures, and a separate social-connect 404. Do not classify all
seven as metadata failures.

The capture contains 75 curiosity queries and one pathfinder query. Across them,
13 operation names occur; two belong to player/Jam behavior and are not needed
for the metadata demo. No GraphQL `errors[*].extensions.code` values were found
by a targeted extraction. Readable successful JSON bodies had no top-level
GraphQL errors. The two HTTP 431 responses had no readable response body.

One whole-object home export was redacted by wiretrail. Narrow field extraction
recovered its paging and type information without disabling redaction. An absent
or redacted export must not be treated as an absent server response.

## Authentication flow

The captured TV flow is a device authorization grant. The phone-side approval
pages are not captured here; the resulting successful token poll is captured.

| Step | Evidence | Observed contract |
| --- | --- | --- |
| Request pairing | `e000050` | POST `accounts.spotify.com/oauth2/device/authorize`; HTTP 200 |
| Wait for approval | `e000053`, `e000055`, `e000057`, `e000059` | POST `/api/token`; HTTP 400 with `authorization_pending` |
| Receive session | `e000061` | Same token endpoint and device grant; HTTP 200 |
| Read account | `e000065` | `getUser` through pathfinder; HTTP 200 |
| Read account home | `e000075` | `homepage` through curiosity; HTTP 200 |

The authorization request uses form fields `client_id`, `scope`, `user_intent`
and `creation_point`, with a `spotify-installation-id` header. Its observed scope
is `umbrella-tv client-authorization-universal` and its intent is `login`.
Browser/WebView transport headers are also present; their presence alone does
not prove each is required for a native request.

The authorization response contains `device_code`, `user_code`,
`verification_uri`, `verification_uri_complete`, `interval` and `expires_in`.
The returned polling interval is five seconds and the challenge lifetime is
3,599 seconds. Use the returned verification URI/code for the host's native QR
surface; never display the private device code.

Each poll supplies `client_id`, `device_code` and
`grant_type=urn:ietf:params:oauth:grant-type:device_code`. The observed intervals
between poll starts are approximately 5.215–5.270 seconds. Four pending responses
precede success; the final response contains `access_token`, `refresh_token`,
`scope`, `token_type` and `expires_in`. The access-token lifetime is 3,600 seconds
and the token type is Bearer. No credential values are copied into this report.

No `client_secret`, cookie or TOTP parameter occurs in these captured
authorization/poll request bodies. This is evidence for the device flow, not a
claim that the entire HAR contains no other sensitive data.

The SDK needs explicit pending, success, denied, expired and canceled states.
Respect server polling intervals; handle `slow_down` by increasing the interval,
and stop polling on terminal outcomes. These additional failure cases are
protocol requirements from [RFC 8628, section 3.5](https://www.rfc-editor.org/rfc/rfc8628.html#section-3.5),
not outcomes observed in this recording.

All five captured token requests use the device-code grant. **There is no
refresh-token exchange in this HAR.** Wiretrail's generic token-endpoint
classification must not be substituted for checking `grant_type`. The earlier
session reported successful refresh, but fresh refresh and rotation tests remain
separate evidence. Do not repeat a login or reuse the captured token just to
fill that gap during read-only analysis.

The plugin should use account-scoped protected storage, one refresh operation
at a time, and session-generation checks so a late token response cannot undo
logout. The host owns QR presentation and lifecycle cancellation; the plugin
owns its provider protocol through the SDK's restricted services.

## Metadata requests

Curiosity requests use `operationName`, JSON `variables`, and persisted-query
`extensions` in the query string. The inspected metadata calls include
`Authorization`, `app-platform: browser` and
`spotify-app-version: 260826040`. They do not carry a `client-token` header.
A separate client-token request exists in the capture; do not assume its token
must be attached to every metadata operation.

Endpoint and request shape are per-operation configuration. `getUser` uses
pathfinder while the browsing operations below use curiosity.

| Operation | Calls / outcomes | Variables | Response root | Example evidence |
| --- | --- | --- | --- | --- |
| `getUser` | 1 × 200 | Empty object | `data.me` | `e000065` |
| `homepage` | 4 × 200 | `endUserIntegration`, `facet`, `limit`, `offset`, `sectionItemLimit`, `timeZone` | `data.home.sectionContainer.sections` | `e000075`, `e000119`, `e000180`, `e000237` |
| `subfeeds` | 1 × 200 | `endUserIntegration`, `timeZone` | `data.home` | `e000076` |
| `artistByUri` | 3 × 200 | `includeTopTrackCoverArt`, `uri` | `data.artistUnion` | `e000321`, `e000621`, `e000997` |
| `albumMetadata` | 1 × 200 | `uri` | `data.albumUnion` | `e001038` |
| `albumTrackList` | 1 × 200 | `excludeVideos`, `limit`, `offset`, `uri` | `data.albumUnion.tracksV2` | `e001034` |
| `playlistMetadata` | 4 × 200 | `uris` | `data.lookupEntities` | `e000472`, `e000733` |
| `playlistTrackList` | 15 × 200 | `excludeVideos`, `limit`, `offset`, `uris` | `data.lists[].items` | `e000470`, `e000731` through `e000961` |
| `libraryMetadata` | 4 × 200 | `limit`, `offset` | `data.me.libraryV3` | `e000688` |
| `librarySection` | 7 × 200 | Optional `filterIds`, `limit`, `offset`, `sortOrderId` | `data.me.libraryV3` | `e000690`, `e000718`, `e000984`, `e001026` |
| `savedStatus` | 27 × 200; 2 × 431 | `uris`, `useDistributionTraits` | `data.lookupEntities` | `e000323`, `e000929`, `e000946`, `e000963` |

### Paging

- Home loads offsets 0, 6, 12 and 18 with `limit=6` and
  `sectionItemLimit=20`. Responses contain 6, 6, 6 and 3 sections: all 21 sections.
  The final `nextOffset` is null.
- Home sections have their own item counts and paging metadata. Retrieving all
  section pages does not prove every section's complete item list was fetched.
- One playlist is captured end to end: offsets 0 through 220 in increments of
  20, then four final items and a null continuation, for 224 items total.
- The album request uses `limit=200` and returns nine tracks. Treat this as an
  observed request size, not a universal provider limit.
- The unfiltered library returns 21 items with a further offset. Only the first
  unfiltered page is present; the filtered playlist, artist and album requests
  reach their respective ends.

Follow response continuations, prevent loops and deduplicate overlapping pages.
Do not assume empty post-filter output means the provider is exhausted: a page
containing only excluded podcast items can still have a valid next cursor.

### Library and content filtering

`libraryMetadata.availableFilters` advertises Playlists, Artists, Albums and
Podcasts. The capture exercises Playlists, Artists and Albums. Observed sorting
IDs include `Recently Added` and `Custom Order`. Return provider identifiers as
opaque control values, with display text kept separate.

The mixed library includes podcast content. Likewise, the home requests use an
empty facet, not `music-chip`. Across their items, targeted extraction found
artist, playlist, album and podcast-episode entity types, including 20 episode
occurrences. The previous session's music-chip experiment remains historical
evidence; this recording does not repeat it.

For the YouTube metadata plugin, omit podcast/episode capabilities, routes,
filters, saved-episode collections and mixed-page items. Apply that rule to
Home, Explore, Search, Library and details. Preserve other supported music
content and the agreed element vocabulary. The SDK itself retains optional
podcast support for other plugins.

## Mapping observed data to the UI contract

| UI requirement | Captured data | Evidence limit |
| --- | --- | --- |
| Recents and regular home sections | One `HomeShortsSectionData` plus 20 `HomeGenericSectionData` sections | These backend types do not define the entire SDK component catalog |
| Square and wide artwork | Home entity visual-identity traits and content imagery | Map explicit presentation choices; do not infer all items are identical cards |
| Artist hero and About | Profile/name/biography, monthly listeners, verification, avatar and full-bleed image | A separate About-specific image is not established |
| Discography and related entities | Albums, singles, compilations, latest/popular releases, top tracks, related artists and featuring playlists | Some nested collections have limited lists; further paging operations need validation |
| Album header and tracks | Artwork, artists, release type/year, saved state, track count and track list | Do not invent durations or popularity values missing from a response |
| Playlist header and table | Name/description, artwork, owner, follower count, following state, total count and ordered items | Desktop added-by/date-added columns and collaborator lists are not established by these responses |
| Stable playlist occurrences | Each item includes a `uid` separate from its entity URI | Use occurrence identity so duplicate tracks do not collapse |
| Library filters and mixed grid | Available filters, sort parameters, typed items and next offsets | Unfiltered library paging is incomplete in the capture |
| Saved-state controls | `savedStatus` returns entity URI plus `isCurated` | Read status is captured; save/remove writes are not |

Artist pick, concert/tour rows, and every desktop biography/metric field are not
present in the inspected artist response shape. The renderer must still support
the screenshot-derived elements. A plugin can supply an optional block only
when it has the corresponding data; omit it otherwise. The TV operations may
need additional metadata operations to reproduce all desktop content.

Playlist metadata uses trait-based entity wrappers; album and artist results use
different nested track shapes. Keep these provider DTOs inside the Spotify
plugin and normalize them into SDK entities and page blocks. Spotify
`playability` means availability in Spotify's own context; it must not enable
MusicViz playback or assert YouTube availability in phase 1.

## Saved-status failure and SDK regression case

While the long playlist is paged, saved-status queries repeatedly include all
accumulated track URIs. The observed request sizes increase from 20 to 197 URIs
successfully; requests with 217 (`e000946`) and 221 (`e000963`) URIs return 431.
The wiretrail-rendered URLs grow while the rendered header sizes stay constant.

Inference: request growth is the likely cause. There is no error response body,
so the exact server threshold and whether a particular intermediary counted the
request target as headers are not established.

The demo should query only newly encountered, uncached IDs, use bounded batches,
and merge saved states by entity reference. For initial validation, batches of
20 align with the captured playlist page size and successful requests; this is
a conservative client choice, not a discovered service maximum. Do not submit
empty saved-status requests, which the captured client also does.

Add a deterministic regression scenario with a playlist exceeding 220 entries:
all pages remain accessible, saved-state request sizes remain bounded, duplicate
URIs are deduplicated for lookup without deleting playlist occurrences, and
failures do not remove already rendered tracks.

## Remaining validation gaps

| Gap | What is missing |
| --- | --- |
| Search and suggestions | No search operations occur among the captured GraphQL requests |
| Save/remove/follow writes | Saved-state reads occur; no `curateItem`/`uncurateItem` mutation is captured |
| Token refresh and rotation | No `grant_type=refresh_token` request |
| Authentication failures | No denied, expired, slow-down, revoked-session or logout sequence |
| Dedicated discovery browsing | No `browseAll`, `browsePageTraits` or `browseSectionTraits` operation |
| Music-chip home | Empty facet in all four home requests |
| Rich desktop artist content | Artist pick and events absent from inspected TV artist DTOs |

Use a targeted follow-up capture or an explicitly authorized live test for these
items; do not ask for another complete capture of already-proven flows. Existing
public bundle definitions can guide request construction, but do not prove that
an operation succeeds or supplies the required payload.

## Reproducibility and handling

The analysis used wiretrail `validate`, `summary`, `endpoints`, `export`, `auth`,
`show-entry` and narrow `extract` queries. Entry IDs in this report belong to
this exact HAR. Detailed output stayed in a private temporary directory, outside
the repository; only allowlisted fields were printed. Default redaction remained
enabled throughout.

Build fixtures from selected structures with synthetic account/entity values.
Do not copy the raw capture or auto-generated full dossier into git. It contains
live account material, and generic redaction is not a reason to publish it.

The temporary outbound port-443 rules added for capture were removed from the
device's IPv4 and IPv6 OUTPUT chains and the resulting rule lists were verified.
The existing Android firewall chains were preserved.
