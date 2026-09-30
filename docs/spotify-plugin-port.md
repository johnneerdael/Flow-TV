# Spotify metadata plugin

Implementation brief, 2026-09-30.

Use the existing `.mbplugin` SDK, runtime, account sign-in and native TV renderer.
Spotify supplies metadata; the host matches track descriptors through the selected
audio plugins. The Spotify plugin has no audio role. This supersedes the older
September 27 proposal's browse-only phase and undecided runtime.

Port the behavior of Meld's Spotify client into TypeScript host-service calls:
Home, Search, artists, albums, playlists, library and Liked Songs. Preserve
provider order, artist portraits, collection artwork, credits and durations with
the existing header, shelf and track-table vocabulary. TV focus and theme come
from the host. The supplied desktop screenshots establish the content hierarchy.

Use the web-cookie account model (`sp_dc`) and server-time TOTP, with HMAC-SHA1
performed by `mb.crypto.hmac`. Store account credentials in host secrets. Renew
once per session, reject stale completions after logout/account replacement, and
preserve the account on transient failure. Anonymous public catalog reads are
supported; Home and Library require sign-in.

Read public TOTP and query-hash updates from the existing `spotify-data` branch,
with a bundled snapshot for outages. The scheduled workflow derives data from
Spotify's public client. Refresh hashes once on a persisted-query failure.
Cache successful requests briefly, bound the cache, and page on demand.

Validate with sanitized captured Home/artist fixtures, anonymous public
album/playlist/Search fixtures, synthetic library/session failure scenarios,
SDK checks, package validation and the existing cross-provider matcher tests.
Account-specific live sign-in and TV playback remain device validation gates.
