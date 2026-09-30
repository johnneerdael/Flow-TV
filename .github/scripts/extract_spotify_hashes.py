#!/usr/bin/env python3
"""Extract the metadata plugin's persisted queries from Spotify's public web client."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from extract_spotify_totp import ExtractionError, SPOTIFY_HOME, fetch_text, script_urls

REQUIRED = ('home', 'homeSection', 'getAlbum', 'getTrack', 'fetchPlaylist', 'queryArtistOverview', 'libraryV3', 'fetchLibraryTracks', 'profileAttributes')


def extract_hashes(source: str) -> dict[str, str]:
    return dict(re.findall(r'\("([A-Za-z][A-Za-z0-9_]*)","query","([a-f0-9]{64})"', source))


def registry(fresh: dict[str, str], fallback: dict[str, str]) -> dict[str, str]:
    missing = [name for name in REQUIRED if name not in fresh]
    if missing:
        raise ValueError(f"required queries missing from Spotify's bundle: {', '.join(missing)}")
    result = {name: fresh[name] for name in REQUIRED}
    # The desktop Search document remains accepted by Pathfinder even though the web bundle
    # no longer publishes it. Keep its independently verified hash until a replacement is known.
    search = fresh.get('searchDesktop') or fallback.get('searchDesktop')
    if not isinstance(search, str) or not re.fullmatch(r'[a-f0-9]{64}', search):
        raise ValueError('no verified searchDesktop query')
    result['searchDesktop'] = search
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        fresh: dict[str, str] = {}
        for url in script_urls(fetch_text(SPOTIFY_HOME)):
            fresh.update(extract_hashes(fetch_text(url)))
            if all(name in fresh for name in REQUIRED):
                break
        snapshot = Path(__file__).resolve().parents[2] / 'plugins/spotify/assets/hashes.json'
        result = registry(fresh, json.loads(snapshot.read_text()))
        args.output.write_text(json.dumps(result, indent=2) + '\n')
        print(f'wrote {len(result)} query hashes to {args.output}', file=sys.stderr)
        return 0
    except (ExtractionError, OSError, ValueError) as error:
        print(f'hash extraction failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
