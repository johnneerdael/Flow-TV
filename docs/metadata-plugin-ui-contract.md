# Metadata plugin UI contract

Status: proposed phase 1 contract, grounded in the user's Spotify and YouTube
Music screenshots on 2026-09-27. This describes the SDK and native renderer to
build; it is not implemented app behavior.

## Ownership and scope

Plugin creators choose the data, elements, presentation types, section order,
filters, and navigation targets. MusicViz supplies the UX: native rendering,
theme, TV sizing, D-pad navigation, accessibility, focus restoration, loading and
error handling. Spotify and YouTube layouts define the initial requirements for
the component catalog, not two fixed provider-specific page templates.

The contract must support full Home, Explore, Search, Library, artist, album,
playlist, and optionally podcast metadata pages. A page can interleave a banner,
track table, editorial card, cover shelf, text panel, and artist portraits.
Do not flatten all these into uniform square-card shelves.

Phase 1 changes metadata and its presentation only. Preserve the existing
playback entity and player behavior. External plugin content is browse-only;
matching, audio plugins, stream resolution, playback integration, queue changes,
download support, and playable previews belong to phase 2.

## Catalog capabilities

Every full metadata provider must support artists, albums, and playlists in both
Search and Library, including navigation to their detail pages. Track/song
metadata remains part of the core music contract. An empty account library is
valid; lack of an implementation must not be reported as an empty account.

Declare supported kinds separately for search, library, and details. Validate
the required artist/album/playlist coverage when activating a full provider.
Expose optional kinds only where that provider implements them.

| Kind | Contract expectation |
| --- | --- |
| Artist | Required search, library and details; related artists and discography are optional sections |
| Album | Required search, library and details; distinguish album, single and EP through release metadata |
| Playlist | Required search, library and details; include provider-curated, personal and collaborative attribution when available |
| Track | Core music metadata, search results, saved songs, and collection contents |
| Podcast show / episode | Optional capability; show filters, cards and detail routes only when declared |
| Creator / profile | Optional, covering YouTube music-channel profiles and collection owners |
| Music video | Optional metadata capability, rendered without adding playback behavior in phase 1 |
| Event | Optional artist metadata such as tour dates, venue and location; no ticket purchasing flow |

The latest optional-podcast requirement supersedes the earlier global podcast
ban at the framework level. The original Spotify music-only configuration can
omit this capability; the host SDK must not hardcode that restriction for every
plugin. Audiobooks are not introduced by the podcast capability.

The YouTube metadata plugin explicitly does not support podcasts. Exclude podcast
shows, episodes, episode collections, podcast filters and their detail routes
across Home, Explore, Search and Library, including mixed results. Keep all other
agreed music metadata and element presentations. Podcast support remains optional
for other third-party plugins, not for the initial YouTube plugin.

Search offers an aggregate result page and type-filtered results. Library offers
a mixed overview and type-filtered collections. Both use the same element
renderers as Home and detail pages. Suggestions, sort modes, within-collection
search, and write actions are individually declared capabilities.

## Page composition

Use an ordered list of typed blocks rather than a list containing only shelves.
The following names describe the proposed schema, not Kotlin classes already in
the repository:

```text
MetadataPage
  id, title, schemaVersion
  blocks[], optional nextCursor

PageBlock
  EntityHeader | Collection | Feature | About | Controls

Collection
  id, optional header, optional controls
  layout, defaultItemView, items[]
  optional columns, optional showAllTarget, optional nextCursor

MetadataItem
  id, target, optional itemView override
  title, optional subtitle, artwork, typed metadata, actions[]

Target
  EntityRef(kind, providerId) | PageRef(pageId, arguments)
```

The host binds the page and its references to the installed plugin and account
session that returned them. A plugin cannot choose another plugin's ownership
by inserting a different identifier into its response.

Block order and item order are meaningful and preserved. A single featured item
does not require a fake shelf title or empty neighboring cards. Collections can
omit their header. Reuse the same block types on all page routes.

### Blocks

| Block | Fields and intended use |
| --- | --- |
| `EntityHeader` | Artist banner, portrait or collection cover; title, entity kind, description, creator attribution, metrics and supported metadata actions |
| `Collection` | A titled or untitled sequence of items using a supported layout; optional local filters, sorting, columns and Show all navigation |
| `Feature` | One editorial/artist-pick element with large artwork, optional cover inset, attribution, context text and metadata target |
| `About` | Biography or descriptive text, optional image and metrics, expandable through a native details surface |
| `Controls` | Filter chips, category tabs, sort selector or supported search-within control, explicitly scoped to a page or collection |

Header variants are bounded: banner, cover-with-details, portrait-with-details,
and text. Plugins select the variant and supply assets; MusicViz handles
readability and responsive positioning using its existing design system.

### Layouts

| Layout | Behavior |
| --- | --- |
| `HORIZONTAL_SHELF` | Horizontal navigation through cards; supports compatible mixed widths and shapes |
| `GRID` | Wrapping collection with responsive column count and pagination |
| `VERTICAL_LIST` | One vertical sequence of compact rows |
| `MULTI_COLUMN_LIST` | Compact rows grouped into columns, as in Quick picks and Long listens |
| `TRACK_TABLE` | Track rows with an ordered set of semantic columns, as in Popular and playlist contents |

Plugins choose the arrangement. MusicViz chooses visible column counts, row
heights and TV-safe sizes. Multi-column reading order is down each column then
across columns; D-pad movement follows visual neighbors. Appending a page must
not reorder already visible items or move the focused item unexpectedly.

Track-table column keys include ordinal/rank, title, artists, album, play count,
added by, date added and duration. The plugin chooses supported columns and their
order; title remains mandatory. The host can move secondary columns into row
details at narrow widths. A sparse payload must not fabricate counts or dates.

### Element presentations

| Element | Presentation |
| --- | --- |
| `COVER_CARD` | Square cover with title and secondary metadata |
| `LANDSCAPE_CARD` | Wide artwork with title and secondary metadata |
| `ARTIST_PORTRAIT` | Circular artist/profile image and text |
| `TRACK_ROW` | Small thumbnail, title, artists and selected secondary fields |
| `SHORTCUT_TILE` | Compact artwork and label for recent or pinned entities |
| `CATEGORY_TILE` | Text and optional supported icon for a browse category |
| `FEATURE_CARD` | Large portrait or landscape editorial artwork with context, optional inset cover and attribution |
| `EPISODE_ROW` | Optional podcast episode metadata, including show, publication date and duration |
| `EVENT_ROW` | Date badge, event title, artist, venue and location |

Layout, element presentation, and entity kind are independent. A playlist may
appear as a cover card, shortcut, or feature card. A mixed Recents shelf can
contain circular artists, square albums and playlists, and podcast items when
enabled. The Massano reference also mixes a square collection cover with wide
video thumbnails in the same shelf; this must not require separate sections.

Use `defaultItemView` to avoid repeating a common view type. Items override it
where the combination is supported. The host validates combinations and applies
consistent alignment without flattening every item to the section default.

Artwork can be a supplied image or a bounded collage of up to four covers. A
collage is an artwork variant reusable in cards and collection headers, not a
new entity kind. Accept optional creator avatars and attribution badges. Source
verification badges identify the provider's assertion, not MusicViz's endorsement
of an arbitrary plugin or publisher.

## Controls, metadata and actions

Page-level and collection-level controls are both needed. Discography's Popular
releases / Singles and EPs filters affect only that collection. Library's entity
filters and recent-activity sorting affect the library page. Applying a local
filter must not reset unrelated sections.

Use opaque filter and sort IDs, explicit selected state, scope, and a
single-/multi-selection mode. The host issues a typed request with the updated
selection. The provider owns its query semantics and next cursor; the host owns
focus, input handling, request cancellation and state restoration.

Optional typed metadata includes release year/type, duration, play/view count,
audience count, saved/followed state, publication/date-added values, explicit
content, attribution, and descriptive text. Format semantic dates and counts
through host formatters; retain provider text where no reliable semantic value
is available. Absent values remain absent.

Phase 1 actions include opening an entity/page, Show all, expanding text, changing
filters/sorting, and saving/removing a saved item where supported. Following is
an optional metadata capability; it must not be implied merely because an artist
page exists. Account mutations require a matching declared capability and an
authenticated session; the host renders pending/failure state and reconciles
with the provider response.

Do not expose arbitrary method names, raw executable commands, host routes, or
Android objects through actions. Do not display Play, Shuffle, Queue, Download,
Preview, or local playback-progress affordances for external plugin content in
phase 1. A provider-supplied resume position, if represented, is a static remote
account fact and must never read or mutate MusicViz's playback state.

## Rendering and lifecycle rules

- Use native MusicViz/Material 3 components and theme tokens. Reference screenshots
  establish information structure and supported views, not permission to add
  gradients, glass effects, provider CSS or arbitrary styling instructions.
- Plugins provide content and view choices. The SDK exposes no pixel coordinates,
  custom fonts, executable UI handlers or unrestricted component nesting.
- Keep plain and contextual headers, optional subtitles, entity avatars and
  Show all actions reusable. Section names such as Discography or Featuring are
  content, not bespoke renderer identifiers.
- Load only visible/needed metadata, cancel obsolete requests, and keep request
  concurrency bounded. No continuously running plugin work for hidden pages.
- Provide native loading, partial-data, empty, error, retry and login-required
  states at the appropriate page or collection scope.
- Preserve stable page/block/item IDs and scope caches/focus state to provider
  and account. One entity can occur in multiple sections: the item occurrence ID
  must be distinct from its entity reference.
- Page pagination and collection pagination are independent. Filter/sort changes
  reset only the relevant cursor; stale responses cannot overwrite newer state.
- Visible images use the existing image loader. Feature cards use static artwork
  in phase 1; the Spotify preview animations do not authorize a new media player.

## Compatibility

Version the UI schema and advertise required blocks, layouts and element types.
Reject activation when the host lacks a required type. Optional types can name a
supported fallback, such as a cover card for an editorial feature. A fallback is
the author's deliberate choice, not automatic flattening by the host.

Validate payload size, counts, IDs, action capabilities, targets, artwork and
entity/view/layout compatibility before rendering. Unknown optional blocks may
be omitted with a diagnostic; unsupported required blocks produce a clear
compatibility state. Never reinterpret an invalid payload as an empty library.

## Reference coverage

All fourteen individual screenshots supplied with the latest request were
inspected. Times below identify `Screenshot 2026-09-27 at <time>.png` on the user's
Desktop; the final row is the full artist-page capture attached to the request.

| Reference | Contract requirements |
| --- | --- |
| 21.21.12 | Descriptive cover shelves; mixed artist/album/playlist/optional-episode recents |
| 21.21.24 | Large editorial cards, context label, inset cover and descriptive text |
| 21.21.44 | Artist banner, verification/audience metadata, ranked track table |
| 21.21.51 | Attributed Artist pick feature; discography with collection-local filters |
| 21.22.00 | Descriptive playlist cards and date/venue/location event rows |
| 21.22.08 | Image-backed biography/About block; discovery playlists |
| 21.22.16 | Circular related artists and Appears on album covers |
| 21.23.08 | Collection header with collage and collaborators; track table with added-by/date/duration |
| 21.23.29 | Landscape favorites followed by square-cover genre playlists |
| 21.23.47 | Compact track rows arranged into columns |
| 21.24.01 | Entity-led header and mixed square/wide elements within one shelf |
| 21.24.08 | Landscape video metadata followed by multi-column track rows |
| 21.24.16 | Community collage artwork, creator avatar attribution and mix covers |
| 21.24.35 | Mixed Library grid; entity filters, sort control, optional podcast category |
| Full TH;EN artist capture | Banner, top-song rows, albums, singles/EPs, videos, featured playlists, creator playlists, related artists |

The screenshots show presentation requirements. They do not prove that a
particular Spotify or YouTube endpoint supplies every field. Confirm data
availability and pagination with the HARs; omit unsupported optional fields and
sections rather than inventing them.

## Acceptance scenarios

1. Render Spotify and YouTube sample pages using the same component catalog,
   without checking the provider name inside renderers.
2. Reorder different block types in a fixture and observe the same order in the
   UI, including a standalone feature and About block between collections.
3. Render mixed square, circular and landscape elements in one shelf; preserve
   their shapes and D-pad focus across paging and returning from details.
4. Exercise Artists, Albums and Playlists in Search and Library for every full
   provider. Verify podcast categories appear only for a capable provider and
   that the YouTube plugin removes podcast/episode items from mixed pages too.
5. Change a discography filter without resetting another section; change a global
   search filter without allowing stale results to reappear.
6. Display track tables with optional columns and missing values on a TV; verify
   row details retain data when columns are condensed.
7. Exercise login expiry, save failure, empty account, page timeout, malformed
   blocks, unknown optional types and an incompatible required schema.
8. Confirm metadata navigation never inserts external IDs into the existing
   playback entity or triggers play, queue, stream or download operations.

Build-time and on-device verification belong to implementation. Only screenshot
inspection and document consistency checks have been performed for this draft.
