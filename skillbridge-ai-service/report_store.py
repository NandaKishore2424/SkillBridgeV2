"""
Keeps each student's latest skill-gap report where the backend can read it.

The report used to be printed to this service's console and nowhere else, so no
screen could show it. Now it is upserted into skill_gap_reports (Flyway V7 in
the backend, which owns the schema) as the document described by
contracts/skill-gap-report/v1/skill-gap-report.schema.json. Both services test
against that schema: this module's output in tests/test_skill_gap_report_contract.py,
the backend's reader in SkillGapReportContractTest.

One row per student, overwritten. Every analysis reads the student's skills as
they are when it runs, so the analysis that ran last is the right one to keep
even if events were delivered out of order, and a redelivery writes the same row
again rather than adding one.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any, Callable, Optional

SCHEMA_VERSION = 1

# An ERROR report is not a result: it is raised as a failure so the consumer
# retries and, in the end, dead-letters it. Only these are stored.
STORED_STATUSES = ("SUCCESS", "SKIPPED")


def report_document(report: Any, analyzed_at: Optional[datetime] = None) -> dict:
    """The contract document for a SkillGapReport."""
    if report.analysis_status not in STORED_STATUSES:
        raise ValueError(f"a {report.analysis_status} report is not stored")
    when = (analyzed_at or datetime.now(timezone.utc)).astimezone(timezone.utc)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "studentId": int(report.student_id),
        "status": report.analysis_status,
        "studentSkills": list(report.student_skills),
        "matchedJobs": [
            {
                "title": job.title,
                "company": job.company,
                "similarity": float(job.similarity_score),
                "matchedSkills": list(job.matched_keywords),
                "missingSkills": list(job.missing_skills),
            }
            for job in report.top_matched_jobs
        ],
        "missingSkills": list(report.all_missing_skills),
        "analyzedAt": when.strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
    }


class ReportStore:
    """The SQL half, against public.skill_gap_reports."""

    def __init__(self, borrow: Callable[[], Any], give_back: Callable[[Any], None]) -> None:
        self._borrow = borrow
        self._give_back = give_back

    def save(self, document: dict, event_id: Optional[str] = None) -> bool:
        """
        Upserts the student's report.

        college_id is read from the student in the same statement, so a report
        can never be filed under another college. Returns False if the student
        no longer exists (deleted between the event and the analysis): there is
        nobody to show the report to, and that is not a failure worth retrying.
        """
        conn = self._borrow()
        try:
            # `with conn` commits on success and rolls back on error.
            with conn, conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO skill_gap_reports
                        (student_id, college_id, status, schema_version, report, source_event_id, analyzed_at)
                    SELECT s.id, s.college_id, %(status)s, %(version)s, %(report)s::jsonb,
                           %(event_id)s::uuid, %(analyzed_at)s::timestamptz AT TIME ZONE 'UTC'
                    FROM students s
                    WHERE s.id = %(student_id)s
                    ON CONFLICT (student_id) DO UPDATE SET
                        college_id = EXCLUDED.college_id,
                        status = EXCLUDED.status,
                        schema_version = EXCLUDED.schema_version,
                        report = EXCLUDED.report,
                        source_event_id = EXCLUDED.source_event_id,
                        analyzed_at = EXCLUDED.analyzed_at
                    """,
                    {
                        "student_id": document["studentId"],
                        "status": document["status"],
                        "version": document["schemaVersion"],
                        "report": json.dumps(document),
                        "event_id": event_id,
                        "analyzed_at": document["analyzedAt"],
                    },
                )
                return cur.rowcount == 1
        finally:
            self._give_back(conn)
