# Security Policy

## Supported versions

Security fixes land on the latest release line only. Older versions do not
receive backported patches.

| Version | Supported |
| ------- | --------- |
| 2.2.x   | ✅ |
| < 2.2.0 | ❌ |

Always update to the newest release before reporting a security issue.

## Reporting a vulnerability

**Do not report security vulnerabilities through public GitHub issues.**

Report privately through
[GitHub Security Advisories](https://github.com/johnneerdael/MusicViz/security/advisories/new).

Please include:

- Type of issue (for example: credential exposure, path traversal, injection).
- Full paths of the source files involved.
- The affected tag, branch, or commit.
- Any configuration required to reproduce the issue.
- Step-by-step reproduction instructions.
- Proof-of-concept or exploit code, if available.
- Impact, including how an attacker might exploit it.

You will receive an acknowledgement within 48 hours and a timeline for a fix.
Please do not disclose the issue publicly until a fix has shipped.

## Verifying release APKs

Official MusicViz builds are signed with a single release key. Any APK that does
not match the fingerprint below is not an official build, regardless of where
it was downloaded.

```
SHA-256: FE:FB:39:D0:D5:F3:DF:3B:BB:D0:B7:CC:F7:FE:D7:16:A0:A1:3E:BD:17:AC:52:9B:0C:1A:8E:C3:C1:F7:A3:F8
```

Verify a downloaded APK with the Android SDK build tools:

```bash
apksigner verify --print-certs musicviz-universal.apk
```

The reported `Signer #1 certificate SHA-256 digest` must equal the fingerprint
above (lower case, without colons).

The official distribution channel is the
[GitHub Releases page](https://github.com/johnneerdael/MusicViz/releases). Builds
obtained anywhere else are unverified.
