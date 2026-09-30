# Spotify TV refresh and follow-up capture

Date: 2026-09-28. Sources: the user's
`HTTPToolkit_2026-09-28_04-36.har`, the local Spotube Spotify plugin reference,
and Spotify's publicly served TV JavaScript bundle. No captured credential was
replayed or copied into this report.

## Finding

The second HAR does not contain the expected Search, saved-item mutation, or
refresh API traffic. Wiretrail found 393 entries over 143.645 seconds, mostly
images and static assets. It found one `api-partner.spotify.com` request for
the TV app document, no GraphQL request, no `accounts.spotify.com` request,
and no POST to `clienttoken.spotify.com`. About 94.4% of entries have response
bodies, but only about 64.7% of POSTs have request bodies. It is not an adequate
fixture for the missing metadata operations. The capture was recorded before
blocking UDP 443 on the currently connected TV, so traffic outside the HTTPS
interception path is a plausible explanation; this is an inference, not something
the HAR can prove.

Wiretrail's generic auth heuristic labels one event as a refresh, but the
endpoint inventory has no token exchange. Do not treat that label as captured
Spotify refresh evidence. The single status-0 image request is not an OAuth
or metadata failure.

## Two different credential models

The local Spotube reference's `RealCoreAPI.kt` restores `sp_dc` cookies and,
near token expiry, fetches a TOTP secret and calls
`https://open.spotify.com/api/token` for a new web-player access token. Its
code keeps the cookie and schedules another run. That is a web cookie flow.

MusicViz's researched Spotify TV login is a device-code grant. The September 27
HAR shows `accounts.spotify.com/oauth2/device/authorize`, polling
`accounts.spotify.com/api/token`, and a response with both an access token and a
refresh token. The `sp_dc`/TOTP renewal code cannot be transplanted to that
session. See the [first capture report](spotify-tv-har-2026-09-27.md).

## Public TV client refresh path

The public [Spotify TV bundle](https://tv.scdn.co/androidtv/v2/f1b1b7e/js/spotifytv.js)
includes a `refreshAccessToken` implementation. Its request uses POST
`https://accounts.spotify.com/api/token` with form fields:

```text
grant_type=refresh_token
refresh_token=<current stored refresh token>
client_id=<TV client ID>
```

The same bundle's refresher updates its stored refresh token when the server
returns a replacement, then supplies the new access token to its caller. It
recognizes an invalid-grant failure separately. The TV app client version,
transport headers and exact response/error shapes still require a successful
refresh capture or an explicitly authorized live test; public code establishes
request construction, not a current account-specific response.

The publicly documented [Spotify refresh flow](https://developer.spotify.com/documentation/web-api/tutorials/refreshing-tokens)
supports the same grant concept, but MusicViz's private TV client uses the
specific endpoint and client configuration above. Use provider-supported HTTP
and serialization libraries; do not hand-build signing, encryption, or a token
format parser.

## SDK and demo implications

- Store Spotify's TV access and refresh tokens as one account-scoped session,
  distinct from YouTube sign-in and from any web-cookie session.
- Refresh shortly before access-token expiry or after one authenticated request
  indicates expiration. Deduplicate simultaneous refresh attempts per session.
- On successful refresh, atomically replace the access token, expiry, and any
  returned refresh token. Keep the existing refresh token if the response has no
  replacement; do not write a blank token.
- Use a session generation/version check so a late response cannot recreate
  credentials after logout, account switch, or a newer refresh.
- Treat invalid grant as a session failure that needs new pairing. Transient
  network errors are retryable without destroying the existing session.
- Never log token values or attach them to metadata page payloads.

These are design requirements; no SDK or refresh code has been implemented.

## Capture setup and remaining evidence

On `192.168.50.80:5555`, root-level IPv4 and IPv6 OUTPUT rules now reject
**UDP destination port 443**. TCP 443 remains open. Both rule lists were
verified after insertion. This changes the TV's live network behavior until
the rules are removed or the device is restarted.

A targeted capture made with these rules active should exercise Search by type,
saved-item add/remove, and a token refresh if naturally available. Avoid
repeating all of Home, artist, album and long-playlist paging: the first HAR
already captures those. Do not assert a refresh occurred solely because the
token endpoint was contacted; inspect the request's `grant_type`.
