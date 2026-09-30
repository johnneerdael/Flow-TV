import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import extract_spotify_hashes as extractor


class HashExtractionTest(unittest.TestCase):
    def test_reads_query_definitions_and_ignores_mutations(self):
        digest = 'a' * 64
        source = f'let x=new a.l("home","query","{digest}",null),y=new b.l("save","mutation","{digest}",null)'
        self.assertEqual(extractor.extract_hashes(source), {'home': digest})

    def test_missing_required_queries_refuse_publication(self):
        with self.assertRaises(ValueError):
            extractor.registry({'home': 'a' * 64}, {'searchDesktop': 'b' * 64})

    def test_preserves_the_verified_legacy_search_query(self):
        fresh = {name: 'a' * 64 for name in extractor.REQUIRED}
        result = extractor.registry(fresh, {'searchDesktop': 'b' * 64, 'unused': 'c' * 64})
        self.assertEqual(result['home'], 'a' * 64)
        self.assertEqual(result['searchDesktop'], 'b' * 64)
        self.assertNotIn('unused', result)


if __name__ == '__main__':
    unittest.main()
