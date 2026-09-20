"""
The AI service's own database role, against a real PostgreSQL.

The service connects with the application's credentials today: full rights to
every table, users and refresh_tokens among them, for a process that needs to
read a student's skills, search the job corpus, claim events and write one
report per student. scripts/db/ai-service-role.sql grants exactly that much.

Both halves are checked here, because either alone is useless: a role that
cannot do the work, or one that can do everything. Needs
AI_TEST_DATABASE_URL, like the rest of this directory; see
test_processed_events_store.py for how to start one.
"""

import json
import os
import sys
import unittest
import uuid
from pathlib import Path

import psycopg2
from psycopg2 import errors

SERVICE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE))

from tests_integration import migrations  # noqa: E402

ROLE_SQL = SERVICE.parent / "scripts" / "db" / "ai-service-role.sql"
PASSWORD = "role-test-only"


def setUpModule():
    global _db, _admin_dsn, _role_dsn, _student_id
    _db = migrations.ThrowawayDatabase("ai_role_it")
    _admin_dsn = _db.create()

    with psycopg2.connect(_admin_dsn) as conn, conn.cursor() as cur:
        cur.execute(ROLE_SQL.read_text())
        # The password is the operator's job in production; the test needs one.
        cur.execute(f"ALTER ROLE skillbridge_ai PASSWORD '{PASSWORD}'")
        _student_id = _seed_student(cur)
    _role_dsn = psycopg2.extensions.make_dsn(_admin_dsn, user="skillbridge_ai", password=PASSWORD)


def _seed_student(cur):
    cur.execute("INSERT INTO colleges (name, code, email, status, created_at, updated_at) "
                "VALUES ('Role College', 'ROLEIT', 'r@role.invalid', 'ACTIVE', now(), now()) RETURNING id")
    college = cur.fetchone()[0]
    cur.execute("INSERT INTO users (email, password_hash, college_id, is_active, created_at, updated_at) "
                "VALUES ('s@role.invalid', 'x', %s, true, now(), now()) RETURNING id", (college,))
    user = cur.fetchone()[0]
    cur.execute("INSERT INTO students (user_id, college_id, full_name, roll_number, created_at, updated_at) "
                "VALUES (%s, %s, 'Role Student', 'ROLE-1', now(), now()) RETURNING id", (user, college))
    student = cur.fetchone()[0]
    cur.execute("INSERT INTO industry_job_descriptions (title, company, required_skills, raw_description, embedding) "
                "VALUES ('Role Job', 'Acme', '', 'needs python and sql', %s::vector)", (_unit_vector(),))
    return student


def _unit_vector():
    return "[" + ",".join(["1"] + ["0"] * 383) + "]"


def tearDownModule():
    if "_db" in globals():
        # The role is cluster-wide, so it outlives the database unless dropped.
        admin_to_this_database = psycopg2.connect(_admin_dsn)
        try:
            admin_to_this_database.autocommit = True
            with admin_to_this_database.cursor() as cur:
                # Policies and grants belong to the role; the database cannot be
                # dropped while they exist, nor while this connection is open.
                cur.execute("DROP OWNED BY skillbridge_ai")
        finally:
            admin_to_this_database.close()
        _db.drop()
        admin = psycopg2.connect(os.environ["AI_TEST_DATABASE_URL"])
        admin.autocommit = True
        try:
            with admin.cursor() as cur:
                cur.execute("DROP ROLE IF EXISTS skillbridge_ai")
        finally:
            admin.close()


class AiRolePrivileges(unittest.TestCase):

    def setUp(self):
        self.conn = psycopg2.connect(_role_dsn)
        self.conn.autocommit = True
        self.addCleanup(self.conn.close)

    def run_as_role(self, statement, params=()):
        with self.conn.cursor() as cur:
            cur.execute(statement, params)
            return cur.fetchall() if cur.description else None

    # --- what the service must be able to do --------------------------------

    def test_it_can_read_the_student_and_their_skills(self):
        self.assertEqual(self.run_as_role("SELECT count(*) FROM students")[0][0], 1)
        self.run_as_role("SELECT s.name FROM student_skills ss JOIN skills s ON ss.skill_id = s.id "
                         "WHERE ss.student_id = %s", (_student_id,))

    def test_it_can_search_the_job_corpus_and_gets_rows_back(self):
        # "It ran" is not enough. industry_job_descriptions has row-level
        # security enabled with no policy of its own, and the default for a role
        # without one is not an error -- it is zero rows, silently. The first
        # version of this test asserted only that the statement executed, and
        # passed while the search returned nothing.
        rows = self.run_as_role("SELECT * FROM search_similar_jobs(%s::vector, -2, 5)",
                                (_unit_vector(),))

        self.assertEqual(len(rows), 1, "the seeded job must come back")

    def test_it_can_claim_an_event_and_store_a_report(self):
        event = str(uuid.uuid4())
        self.run_as_role("INSERT INTO processed_events (consumer, event_id, status, attempts, lease_until) "
                         "VALUES ('role-test', %s::uuid, 'IN_PROGRESS', 1, now())", (event,))
        # Exactly what dedup.complete() writes; the table's CHECK ties DONE to
        # completed_at, so a half-written update is refused by the database.
        self.run_as_role("UPDATE processed_events SET status = 'DONE', completed_at = now(), "
                         "lease_until = now() WHERE event_id = %s::uuid", (event,))
        self.run_as_role("DELETE FROM processed_events WHERE event_id = %s::uuid", (event,))

        report = json.dumps({"schemaVersion": 1, "studentId": _student_id, "status": "SKIPPED",
                             "studentSkills": [], "matchedJobs": [], "missingSkills": [],
                             "analyzedAt": "2026-09-20T10:00:00Z"})
        self.run_as_role("INSERT INTO skill_gap_reports (student_id, college_id, status, schema_version, "
                         "report, analyzed_at) SELECT id, college_id, 'SKIPPED', 1, %s::jsonb, now() "
                         "FROM students WHERE id = %s", (report, _student_id))
        self.assertEqual(self.run_as_role("SELECT count(*) FROM skill_gap_reports")[0][0], 1)

    # --- what it must not ----------------------------------------------------

    def test_it_cannot_read_accounts_or_sessions_or_the_audit_trail(self):
        for table in ("users", "refresh_tokens", "audit_log", "colleges", "batches"):
            with self.subTest(table=table):
                with self.assertRaises(errors.InsufficientPrivilege):
                    self.run_as_role(f"SELECT * FROM {table} LIMIT 1")

    def test_it_cannot_change_a_student_or_the_corpus(self):
        with self.assertRaises(errors.InsufficientPrivilege):
            self.run_as_role("UPDATE students SET full_name = 'changed' WHERE id = %s", (_student_id,))
        with self.assertRaises(errors.InsufficientPrivilege):
            self.run_as_role("INSERT INTO industry_job_descriptions (title, raw_description) "
                             "VALUES ('injected', 'injected')")
        with self.assertRaises(errors.InsufficientPrivilege):
            self.run_as_role("DELETE FROM skill_gap_reports")


if __name__ == "__main__":
    unittest.main()
