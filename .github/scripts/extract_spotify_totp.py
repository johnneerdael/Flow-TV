#!/usr/bin/env python3
"""Derive the Spotify web-player TOTP secrets from Spotify's own JS bundle.

The web player (open.spotify.com) ships its TOTP seeds inside the main
`web-player.<hash>.js` bundle as a list of `{secret:<seed>,version:<n>}`
objects. A seed is either a JS string literal or an array of numbers. The
bundle turns each seed into the HMAC-SHA1 key like this:

    codes  = [ord(c) for c in seed]            # or the numbers themselves
    digits = "".join(str(b ^ (i % 33 + 9)) for i, b in enumerate(codes))
    key    = digits.encode("utf-8")            # raw TOTP key bytes

and generates an RFC 6238 TOTP (SHA1, 30 s period, 6 digits) with the first
entry of the list, sending its version as `totpVer`. The published secret is
`key` Base32-encoded without padding, the same shape the community gist uses.

Output: a JSON array `[{"v": <int>, "s": "<base32>", "keyHex": "<hex>"}, ...]` sorted by
ascending version, so the current (highest) version is the LAST entry.
Consumers should pick the entry with the maximum `v`.

The highest version is validated against Spotify before anything is written:
an anonymous `GET /api/token?reason=init&...` with a correct TOTP returns an
anonymous access token, a wrong one does not. The script exits non-zero when
no secret can be extracted or validated, so a broken run never overwrites a
good file.

Standard library only. Usage: extract_spotify_totp.py [--output PATH]
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import re
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SPOTIFY_HOME = "https://open.spotify.com"
CDN_BASE = "https://open.spotifycdn.com/cdn/build/web-player/"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
)
TIMEOUT = 30

_JS_STRING = r"""'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*"|`(?:[^`\\$]|\\.)*`"""
_NUM_ARRAY = r"\[\s*\d+(?:\s*,\s*\d+)*\s*\]"
_SEED = rf"(?P<seed>{_JS_STRING}|{_NUM_ARRAY})"
_VERSION = r"(?P<version>\d+)"
SECRET_PATTERNS = (
    re.compile(rf"\{{\s*secret\s*:\s*{_SEED}\s*,\s*version\s*:\s*{_VERSION}\s*\}}"),
    re.compile(rf"\{{\s*version\s*:\s*{_VERSION}\s*,\s*secret\s*:\s*{_SEED}\s*\}}"),
)
_SIMPLE_ESCAPES = {
    "n": "\n",
    "t": "\t",
    "r": "\r",
    "b": "\b",
    "f": "\f",
    "v": "\v",
    "0": "\0",
}


class ExtractionError(Exception):
    pass


def http_get(url: str, headers: dict[str, str] | None = None) -> tuple[int, bytes]:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def fetch_text(url: str) -> str:
    status, body = http_get(url)
    if status != 200:
        raise ExtractionError(f"GET {url} returned HTTP {status}")
    return body.decode("utf-8", errors="replace")


def decode_js_string(literal: str) -> str:
    """Decode a JS string literal (quotes included) to its runtime value."""
    body = literal[1:-1]
    out: list[str] = []
    i = 0
    while i < len(body):
        char = body[i]
        if char != "\\":
            out.append(char)
            i += 1
            continue
        escape = body[i + 1]
        i += 2
        if escape in _SIMPLE_ESCAPES:
            out.append(_SIMPLE_ESCAPES[escape])
        elif escape == "x":
            out.append(chr(int(body[i : i + 2], 16)))
            i += 2
        elif escape == "u" and body[i] == "{":
            end = body.index("}", i)
            out.append(chr(int(body[i + 1 : end], 16)))
            i = end + 1
        elif escape == "u":
            out.append(chr(int(body[i : i + 4], 16)))
            i += 4
        elif escape == "\n":
            pass
        else:
            out.append(escape)
    return "".join(out)


def seed_codes(seed_source: str) -> list[int]:
    """Turn a seed as written in the bundle into the numbers the bundle XORs."""
    if seed_source.startswith("["):
        return [int(n) for n in re.findall(r"\d+", seed_source)]
    value = decode_js_string(seed_source)
    # JS charCodeAt works on UTF-16 code units.
    utf16 = value.encode("utf-16-le", errors="surrogatepass")
    return list(struct.unpack(f"<{len(utf16) // 2}H", utf16))


def derive_secret(codes: list[int]) -> str:
    """Seed numbers -> Base32 (unpadded) TOTP secret."""
    digits = "".join(str(b ^ (i % 33 + 9)) for i, b in enumerate(codes))
    return base64.b32encode(digits.encode("utf-8")).decode("ascii").rstrip("=")


def extract_secrets(js: str) -> dict[int, str]:
    found: dict[int, str] = {}
    for pattern in SECRET_PATTERNS:
        for match in pattern.finditer(js):
            codes = seed_codes(match.group("seed"))
            if codes:
                found[int(match.group("version"))] = derive_secret(codes)
    return found


def script_urls(html: str) -> list[str]:
    urls = re.findall(r'https://open\.spotifycdn\.com/cdn/build/web-player/[\w.~-]+\.js', html)
    main = [u for u in urls if re.search(r"/web-player\.[0-9a-f]+\.js$", u)]
    return list(dict.fromkeys(main + urls))


def chunk_urls(js: str) -> list[str]:
    candidates = re.findall(r'\{(\d+:"[0-9a-f]{8}"(?:,\d+:"[0-9a-f]{8}")*)\}', js)
    if not candidates:
        return []
    pairs = re.findall(r'(\d+):"([0-9a-f]{8})"', max(candidates, key=len))
    return [f"{CDN_BASE}{chunk_id}.{chunk_hash}.js" for chunk_id, chunk_hash in pairs]


def find_secrets() -> tuple[dict[int, str], str]:
    html = fetch_text(SPOTIFY_HOME)
    scripts = script_urls(html)
    if not scripts:
        raise ExtractionError("no web-player script found on open.spotify.com")
    main_js = ""
    for url in scripts:
        js = fetch_text(url)
        if not main_js and "/web-player." in url:
            main_js = js
        secrets = extract_secrets(js)
        if secrets:
            return secrets, url
    for url in chunk_urls(main_js):
        try:
            js = fetch_text(url)
        except (ExtractionError, OSError):
            continue
        if "secret" not in js:
            continue
        secrets = extract_secrets(js)
        if secrets:
            return secrets, url
    raise ExtractionError("no {secret, version} entries found in the web-player bundle or its chunks")


def totp(secret_b32: str, timestamp: float, period: int = 30, digits: int = 6) -> str:
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    counter = struct.pack(">Q", int(timestamp) // period)
    digest = hmac.new(key, counter, hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF
    return str(code % 10**digits).zfill(digits)


def server_time() -> float:
    status, body = http_get(f"{SPOTIFY_HOME}/api/server-time")
    if status == 200:
        try:
            value = float(json.loads(body)["serverTime"])
            if value > 0:
                return value
        except (ValueError, KeyError, TypeError):
            pass
    return time.time()


def validate(version: int, secret: str) -> tuple[bool, str]:
    """Anonymous token request; only a correct TOTP yields an anonymous token."""
    now = server_time()
    otp = totp(secret, now)
    query = urllib.parse.urlencode(
        {
            "reason": "init",
            "productType": "web-player",
            "totp": otp,
            "totpServer": otp,
            "totpVer": str(version),
        }
    )
    status, body = http_get(
        f"{SPOTIFY_HOME}/api/token?{query}",
        headers={"Accept": "application/json", "Referer": f"{SPOTIFY_HOME}/"},
    )
    try:
        payload = json.loads(body)
    except ValueError:
        payload = {}
    ok = (
        status == 200
        and isinstance(payload, dict)
        and payload.get("isAnonymous") is True
        and bool(payload.get("accessToken"))
        and "totpVerExpired" not in payload
    )
    summary = f"HTTP {status}, isAnonymous={payload.get('isAnonymous') if isinstance(payload, dict) else None}"
    if isinstance(payload, dict) and "totpVerExpired" in payload:
        summary += ", totpVerExpired present"
    return ok, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--output", type=Path, help="write the JSON here (default: stdout)")
    args = parser.parse_args()

    try:
        secrets, source = find_secrets()
    except (ExtractionError, OSError) as error:
        print(f"extraction failed: {error}", file=sys.stderr)
        return 1

    versions = sorted(secrets)
    print(f"found versions {versions} in {source.rsplit('/', 1)[-1]}", file=sys.stderr)
    latest = versions[-1]
    try:
        ok, summary = validate(latest, secrets[latest])
    except OSError as error:
        print(f"validation request failed: {error}", file=sys.stderr)
        return 1
    print(f"validation of v{latest}: {summary}", file=sys.stderr)
    if not ok:
        print("latest secret did not validate; refusing to publish", file=sys.stderr)
        return 1

    document = json.dumps([
        {"v": v, "s": secrets[v], "keyHex": base64.b32decode(secrets[v] + "=" * (-len(secrets[v]) % 8)).hex()}
        for v in versions
    ], indent=2) + "\n"
    if args.output:
        args.output.write_text(document, encoding="utf-8")
        print(f"wrote {len(versions)} versions to {args.output}", file=sys.stderr)
    else:
        sys.stdout.write(document)
    return 0


if __name__ == "__main__":
    sys.exit(main())
