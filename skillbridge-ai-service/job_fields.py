"""
Reading a stored job posting's fields, as opposed to its prose.

The 1,500 postings came from a Kaggle scrape of a job board, and the scrape put
two things in one column: `company` holds the employer's name, a newline, and
the employer's star rating.

    Vera Institute of Justice\n3.2
    Squarespace\n3.4

Measured 2026-09-20: 1,315 of the 1,500 are like this, so it is the normal case
rather than a handful of bad rows. Every skill-gap report names its top five
matching employers, and HTML collapses that newline, so a student's dashboard
read "Squarespace 3.4" -- a number with nothing to say what it is.

Fixed here, on the way out, rather than by rewriting the column. The stored
corpus is the only copy of the embeddings: its source CSV is gone, so a cleanup
pass over it is a one-way change to data that cannot be regenerated, in exchange
for something a read can do just as well. Reading it also means a posting
imported tomorrow with the same defect is handled without a second migration.

No model or LangChain imports, so the fast test tier can exercise it.
"""

from __future__ import annotations


def employer_name(company: str | None) -> str:
    """
    The employer's name from a stored `company` value.

    The first line, trimmed. The other 185 rows carry no rating and come back
    unchanged apart from surrounding whitespace.

    A missing company becomes the empty string. The column is nullable and no
    stored row is null today, but main.py already carries a fix for a None
    company that failed an event once, so the case is real rather than
    theoretical.
    """
    if not company:
        return ""
    return company.split("\n", 1)[0].strip()
