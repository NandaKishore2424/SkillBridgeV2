"""
ReportStore against a real PostgreSQL, built from the backend's Flyway migrations
(skill_gap_reports is V7).

Needs AI_TEST_DATABASE_URL, like the rest of this directory; see
test_processed_events_store.py for how to start one. Fails, never skips.
"""

import json
import sys
import unittest
import uuid
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import psycopg2
from psycopg2 import pool

SERVICE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE))

import report_store  # noqa: E402
from tests_integration import migrations  # noqa: E402


def setUpModule():
    global _db, _pool
    _db = migrations.ThrowawayDatabase("report_it")
    _pool = pool.ThreadedConnectionPool(1, 4, _db.create())


def tearDownModule():
    if "_pool" in globals():
        _pool.closeall()
    if "_db" in globals():
        _db.drop()


def query(statement, params=()):
    conn = _pool.getconn()
    try:
        with conn, conn.cursor() as cur:
            cur.execute(statement, params)
            return cur.fetchall() if cur.description else None
    finally:
        _pool.putconn(conn)


def report(student_id, skills, missing):
    # skill_analyzer's attribute names, without importing it: it loads LangChain,
    # which this CI job does not install.
    job = SimpleNamespace(title="Data Engineer", company="Acme", similarity_score=0.6,
                          matched_keywords=skills[:1], missing_skills=missing)
    return SimpleNamespace(student_id=student_id, student_skills=skills, top_matched_jobs=[job],
                           all_missing_skills=missing, analysis_status="SUCCESS", error_message="")


class ReportStoreTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        # A decoy college created first, so "the lowest id" or "any college" is
        # not accidentally ours. Without it a report filed under the wrong college
        # passed this test (checked 2026-09-19).
        query("INSERT INTO colleges (name, code, email, status, created_at, updated_at) "
              "VALUES ('Decoy College', 'DECOYIT', 'd@report.invalid', 'ACTIVE', now(), now())")
        college = query("INSERT INTO colleges (name, code, email, status, created_at, updated_at) "
                        "VALUES ('Report College', 'REPORTIT', 'r@report.invalid', 'ACTIVE', now(), now()) "
                        "RETURNING id")[0][0]
        user = query("INSERT INTO users (email, password_hash, college_id, is_active, created_at, updated_at) "
                     "VALUES ('s@report.invalid', 'x', %s, true, now(), now()) RETURNING id", (college,))[0][0]
        cls.college = college
        cls.student = query("INSERT INTO students (user_id, college_id, full_name, roll_number, created_at, updated_at) "
                            "VALUES (%s, %s, 'Report Student', 'RS-1', now(), now()) RETURNING id",
                            (user, college))[0][0]
        cls.store = report_store.ReportStore(_pool.getconn, _pool.putconn)

    def setUp(self):
        query("DELETE FROM skill_gap_reports")

    def test_the_report_is_stored_under_the_students_own_college(self):
        event_id = str(uuid.uuid4())
        document = report_store.report_document(report(self.student, ["java"], ["python"]),
                                                datetime(2026, 9, 19, 16, 10, 5, tzinfo=timezone.utc))

        self.assertTrue(self.store.save(document, event_id))

        college, status, stored, source, analyzed = query(
            "SELECT college_id, status, report, source_event_id::text, analyzed_at FROM skill_gap_reports "
            "WHERE student_id = %s", (self.student,))[0]
        self.assertEqual(college, self.college)
        self.assertEqual(status, "SUCCESS")
        self.assertEqual(stored, document)
        self.assertEqual(source, event_id)
        self.assertEqual(analyzed, datetime(2026, 9, 19, 16, 10, 5))

    def test_a_later_analysis_replaces_the_earlier_one(self):
        self.store.save(report_store.report_document(report(self.student, ["java"], ["python"])))
        self.store.save(report_store.report_document(report(self.student, ["java", "python"], ["spark"])))

        rows = query("SELECT report FROM skill_gap_reports WHERE student_id = %s", (self.student,))
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][0]["missingSkills"], ["spark"])

    def test_a_student_who_no_longer_exists_is_not_an_error(self):
        document = report_store.report_document(report(999_999, ["java"], []))

        self.assertFalse(self.store.save(document))
        self.assertEqual(query("SELECT count(*) FROM skill_gap_reports")[0][0], 0)

    def test_the_database_refuses_a_status_that_is_not_a_result(self):
        with self.assertRaises(psycopg2.errors.CheckViolation):
            query("INSERT INTO skill_gap_reports (student_id, college_id, status, schema_version, report, analyzed_at) "
                  "VALUES (%s, %s, 'ERROR', 1, %s::jsonb, now())",
                  (self.student, self.college, json.dumps({})))


if __name__ == "__main__":
    unittest.main()
