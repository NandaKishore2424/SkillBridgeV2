"""
The shared token that guards this service's HTTP endpoints.

Anyone who could reach :8000 could run an analysis or read the metrics: the
endpoints had no authentication at all. The analysis is expensive (an embedding
and a vector search per call) and its reply describes what a named student can
and cannot do, so an open endpoint is both a denial-of-service lever and a
disclosure.

It fails closed. With AI_API_TOKEN unset the guarded endpoints answer 503, not
200: a service that quietly runs unauthenticated because a variable is missing
is how "secured in production" turns out not to be.

Liveness and readiness stay open -- a probe has no credentials, and they report
up or down and nothing else.

No FastAPI, model or database imports, so the fast test tier can exercise it.
"""

from __future__ import annotations

import hmac
import os
from typing import Optional

HEADER = "X-Service-Token"

UNSET = "the AI service token is not configured; set AI_API_TOKEN"
WRONG = f"a valid {HEADER} header is required"


class TokenRejected(Exception):
    """Raised with the status the caller should get."""

    def __init__(self, status_code: int, detail: str) -> None:
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


def expected_token() -> Optional[str]:
    token = os.environ.get("AI_API_TOKEN", "").strip()
    return token or None


def check(presented: Optional[str], expected: Optional[str]) -> None:
    """
    Raises TokenRejected unless `presented` is exactly `expected`.

    Compared with hmac.compare_digest: == on strings returns as soon as two
    characters differ, and the time that takes is a measurement an attacker can
    make.
    """
    if not expected:
        raise TokenRejected(503, UNSET)
    if not presented or not hmac.compare_digest(presented, expected):
        raise TokenRejected(401, WRONG)
