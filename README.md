<div align="center">

# MusicViz

**A music-first YouTube Music client for Android TV.**

<a href="https://github.com/johnneerdael/MusicViz/releases/latest">
  <img src="https://img.shields.io/github/v/release/johnneerdael/MusicViz?style=for-the-badge&color=crimson&label=Latest%20build">
</a>
<img src="https://img.shields.io/badge/Platform-Android_TV_8.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white">
<a href="License">
  <img src="https://img.shields.io/badge/License-GPL_v3.0-blue?style=for-the-badge&logo=gnu-bash&logoColor=white">
</a>
<a href="#cert">
  <img src="https://img.shields.io/badge/Security-Verified_SHA--256-blue?style=for-the-badge&logo=security&logoColor=white">
</a>

</div>

---

MusicViz turns your TV into a music player for YouTube Music. It opens straight on your
YouTube Music home — the same mood chips and shelves, in the same order as
music.youtube.com — built for the remote with Jetpack Compose and Material 3.

Search starts on music (songs, artists, albums) and can switch to videos, so live sets and
concert recordings are one press away.

## Install on Android TV

| Method | How |
|---|---|
| **Downloader app** (Google TV, Fire TV, NVIDIA SHIELD) | Install [Downloader](https://www.aftvnews.com/downloader/) and enter code **`4718521`** |
| **Direct link** (always the latest build) | https://github.com/johnneerdael/MusicViz/releases/latest/download/musicviz-universal.apk |
| **All builds** | [Releases](https://github.com/johnneerdael/MusicViz/releases) — every push to `main` is published automatically with release notes |

Per-ABI APKs (`musicviz-arm64-v8a.apk`, `musicviz-armeabi-v7a.apk`) are
attached to each release if you prefer a smaller download.

**Requirements:** Android TV / Google TV / Fire TV running Android 8.0 or newer.

## Features

### Music
- **Your YouTube Music home on the TV:** mood chips, then every shelf YouTube Music shows you
  (Quick picks, Long listens, mixes, albums, new releases, music videos, …) in its order,
  loaded all the way to the end
- **Sign in with your phone:** Settings → Account → *Sign in with phone* shows a QR code; scan
  it on a phone on the same network and log in there. Music and Library then show your own
  YouTube Music feeds. Without signing in you get the regular YouTube Music home.
- Full-screen now playing with synchronized lyrics and an editable queue
- Artist, album and playlist pages; a mini player that follows you through the app
- Background playback on by default, so audio keeps going when you leave the app

### Search
- Opens on **Music** every time, with songs / artists / albums filters
- **Videos** for everything else, with channel, playlist and live filters — handy for DJ sets
  and live recordings

### Library
- History, likes, watch later and playlists, plus your account's library when signed in

### Privacy
- Playback stays anonymous: a signed-in account is only used to read your feeds, and plays are
  not added to your YouTube history
- An on-device recommendation engine; nothing is sent to a server
- No ads, analytics or tracking

## Verifying authenticity
<a id="cert"></a>

Check the signing certificate of a downloaded APK with a tool such as
[AppVerifier](https://github.com/soupslurpr/AppVerifier).

**MusicViz release certificate SHA-256 fingerprint:**
`FE:FB:39:D0:D5:F3:DF:3B:BB:D0:B7:CC:F7:FE:D7:16:A0:A1:3E:BD:17:AC:52:9B:0C:1A:8E:C3:C1:F7:A3:F8`

## Built on Flow

MusicViz is a fork of [Flow](https://github.com/A-EDev/Flow) by A-EDev, which provides the
groundwork this app is built on: its player, YouTube and YouTube Music extraction, and its
on-device recommendation engine.

Flow in turn builds on these projects:

- **[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)** and
  **[NewPipe](https://github.com/TeamNewPipe/NewPipe)** — YouTube data extraction
- **[PipePipe](https://codeberg.org/NullPointerException/PipePipe)** and its
  [developer docs](https://priveetee.github.io/Docs-PipePipe/) — SABR and InnerTube playback
- **[Metrolist](https://github.com/MetrolistGroup/Metrolist)** — hybrid music fetching and lyrics
- **[LibreTube](https://github.com/LibreTube/LibreTube)** — SponsorBlock and DeArrow handling
- **[Media3 / ExoPlayer](https://github.com/androidx/media)**,
  **[Jetpack Compose](https://developer.android.com/jetpack/compose)** and
  **[Material Design 3](https://m3.material.io/)**

## License

MusicViz is free software under the **GNU General Public License v3** — see [License](License).
Any project that uses this source code, including the recommendation engine, must also be
released under the GPLv3.

Copyright © 2025-2026 A-EDev (Flow) · Copyright © 2026 John Neerdael (MusicViz changes)
