"""Offline tests for extract_spotify_totp. Run: python3 -m unittest discover .github/scripts"""

import base64
import sys
import unittest
import json
import tempfile
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import extract_spotify_totp as extractor  # noqa: E402


class DeriveSecretTest(unittest.TestCase):
    def test_xor_join_and_base32(self):
        # 0^9, 1^10, 2^11 -> "9", "11", "9" -> b"9119"
        self.assertEqual(extractor.derive_secret([0, 1, 2]), "HEYTCOI")

    def test_string_seed_matches_numeric_seed(self):
        codes = extractor.seed_codes("'Password'")
        self.assertEqual(codes, [80, 97, 115, 115, 119, 111, 114, 100])
        # "8910712012712297125116" Base32-encoded, padding stripped
        self.assertEqual(extractor.derive_secret(codes), "HA4TCMBXGEZDAMJSG4YTEMRZG4YTENJRGE3A")

    def test_xor_key_wraps_every_33_bytes(self):
        # index 32 is XORed with 41, index 33 wraps back to 9
        secret = extractor.derive_secret([0] * 32 + [41, 9])
        digits = base64.b32decode(secret + "=" * (-len(secret) % 8)).decode()
        self.assertEqual(digits, "".join(str(i + 9) for i in range(32)) + "00")


class JsLiteralTest(unittest.TestCase):
    def test_escapes(self):
        self.assertEqual(extractor.decode_js_string(r"""'a\'b\\c\x41B\u{43}'"""), "a'b\\cABC")
        self.assertEqual(extractor.decode_js_string(r'''"q\"z"'''), 'q"z')


class ExtractSecretsTest(unittest.TestCase):
    def test_finds_string_and_array_seeds_in_either_key_order(self):
        js = (
            'var x=1;let ej=[{secret:\'Pass\\\'word\',version:7},'
            '{secret:[0,1,2],version:6},{version:5,secret:"Password"}].map(e=>e)[0];'
        )
        found = extractor.extract_secrets(js)
        self.assertEqual(sorted(found), [5, 6, 7])
        self.assertEqual(found[6], "HEYTCOI")
        self.assertEqual(found[5], "HA4TCMBXGEZDAMJSG4YTEMRZG4YTENJRGE3A")

    def test_ignores_unrelated_secret_keys(self):
        self.assertEqual(extractor.extract_secrets("{secret:e,algorithm:t}"), {})


class TotpTest(unittest.TestCase):
    # RFC 6238 appendix B, SHA1 seed "12345678901234567890", truncated to 6 digits.
    SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    def test_rfc6238_vectors(self):
        self.assertEqual(extractor.totp(self.SEED, 59), "287082")
        self.assertEqual(extractor.totp(self.SEED, 1111111109), "081804")
        self.assertEqual(extractor.totp(self.SEED, 1234567890), "005924")

    def test_published_secret_includes_the_raw_key_for_host_hmac(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'totp.json'
            with patch.object(sys, 'argv', ['extractor', '--output', str(path)]), patch.object(extractor, 'find_secrets', return_value=({1: self.SEED}, 'fixture.js')), patch.object(extractor, 'validate', return_value=(True, 'fixture')):
                self.assertEqual(extractor.main(), 0)
            data = json.loads(path.read_text())
            self.assertEqual(data[0].get('keyHex'), b'12345678901234567890'.hex())


if __name__ == "__main__":
    unittest.main()
