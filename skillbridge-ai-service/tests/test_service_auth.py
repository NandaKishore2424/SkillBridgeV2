"""
The token that guards this service's HTTP endpoints.

Until 2026-09-20 there was none: anyone who could reach :8000 could run an
analysis or read the metrics.
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import service_auth  # noqa: E402


class CheckToken(unittest.TestCase):

    def test_the_right_token_passes(self):
        service_auth.check("s3cret", "s3cret")  # no exception

    def test_a_wrong_or_missing_token_is_401(self):
        for presented in ("wrong", "", None, "s3cre", "s3cret "):
            with self.subTest(presented=presented):
                with self.assertRaises(service_auth.TokenRejected) as rejected:
                    service_auth.check(presented, "s3cret")
                self.assertEqual(rejected.exception.status_code, 401)

    def test_no_token_configured_fails_closed(self):
        # 503, not "allow": a service that runs unauthenticated because a
        # variable is missing is the failure this guards against.
        for expected in (None, ""):
            with self.subTest(expected=expected):
                with self.assertRaises(service_auth.TokenRejected) as rejected:
                    service_auth.check("anything", expected)
                self.assertEqual(rejected.exception.status_code, 503)

    def test_the_token_comes_from_the_environment(self):
        import os
        os.environ["AI_API_TOKEN"] = "  from-env  "
        try:
            self.assertEqual(service_auth.expected_token(), "from-env")
        finally:
            del os.environ["AI_API_TOKEN"]
        self.assertIsNone(service_auth.expected_token())


if __name__ == "__main__":
    unittest.main()
