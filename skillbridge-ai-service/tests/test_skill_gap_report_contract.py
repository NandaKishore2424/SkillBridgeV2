"""
The skill-gap report this service stores, against the contract the backend reads.

contracts/skill-gap-report/v1 is the one description both sides test against:
here, that what report_store writes validates; in the backend
(SkillGapReportContractTest), that the examples parse into what the screen shows.
Neither side can change the shape without the other's test going red.

Also the consumer's handling of an analysis: an ERROR is raised, so the event is
retried and finally dead-lettered, and never stored as if it were a result.
"""

import json
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import jsonschema

SERVICE_ROOT = Path(__file__).resolve().parents[1]
CONTRACT = SERVICE_ROOT.parent / "contracts" / "skill-gap-report" / "v1"

sys.path.insert(0, str(SERVICE_ROOT))
import analysis_handler  # noqa: E402
import report_store  # noqa: E402

# Stand-ins with skill_analyzer's attribute names. Importing skill_analyzer itself
# would pull in LangChain and the embedding model, which CI's fast tier does not
# install -- this file passed locally and would not have run there (trap 25).


def SkillGapReport(student_id, student_skills, top_matched_jobs, all_missing_skills,
                   analysis_status="SUCCESS", error_message=""):
    return SimpleNamespace(student_id=student_id, student_skills=student_skills,
                           top_matched_jobs=top_matched_jobs, all_missing_skills=all_missing_skills,
                           analysis_status=analysis_status, error_message=error_message)


def MatchedJob(title, company, similarity_score, matched_keywords, missing_skills):
    return SimpleNamespace(title=title, company=company, similarity_score=similarity_score,
                           matched_keywords=matched_keywords, missing_skills=missing_skills)


def load(name):
    return json.loads((CONTRACT / name).read_text())


SCHEMA = load("skill-gap-report.schema.json")


def validate(document):
    jsonschema.Draft202012Validator(SCHEMA, format_checker=jsonschema.FormatChecker()).validate(document)


def success_report():
    return SkillGapReport(
        student_id=42,
        student_skills=["java", "sql"],
        top_matched_jobs=[MatchedJob(title="Data Engineer", company=None, similarity_score=0.592,
                                     matched_keywords=["sql"], missing_skills=["python"])],
        all_missing_skills=["python"],
    )


class ReportDocumentContract(unittest.TestCase):

    def test_examples_are_valid(self):
        for name in ("success.example.json", "skipped.example.json"):
            with self.subTest(name):
                validate(load(name))

    def test_a_success_report_validates(self):
        document = report_store.report_document(success_report(), datetime(2026, 9, 19, 16, 10, tzinfo=timezone.utc))
        validate(document)
        self.assertEqual(document["matchedJobs"][0]["missingSkills"], ["python"])
        self.assertEqual(document["analyzedAt"], "2026-09-19T16:10:00.000000Z")

    def test_a_skipped_report_validates(self):
        skipped = SkillGapReport(student_id=43, student_skills=[], top_matched_jobs=[], all_missing_skills=[],
                                 analysis_status="SKIPPED", error_message="no skills")
        validate(report_store.report_document(skipped))

    def test_an_error_report_is_not_a_document(self):
        failed = SkillGapReport(student_id=44, student_skills=["java"], top_matched_jobs=[], all_missing_skills=[],
                                analysis_status="ERROR", error_message="connection refused")
        with self.assertRaises(ValueError):
            report_store.report_document(failed)

    def test_the_schema_is_strict(self):
        document = report_store.report_document(success_report())
        document["unexpected"] = True
        with self.assertRaises(jsonschema.ValidationError):
            validate(document)


class ConsumerStoresOnlyResults(unittest.TestCase):
    """analysis_handler.analyse_and_store, as main._analyse calls it."""

    def event(self, skills=("java",)):
        return SimpleNamespace(student_id=42, skills=list(skills), event_id="3f1c2b8e-0c3a-4d6e-9f10-2a4b6c8d0e12")

    def test_a_result_is_stored_with_its_event(self):
        store = mock.Mock()
        analysis_handler.analyse_and_store(self.event(), lambda **_: success_report(), None, store)

        document, event_id = store.save.call_args.args
        validate(document)
        self.assertEqual(event_id, "3f1c2b8e-0c3a-4d6e-9f10-2a4b6c8d0e12")

    def test_a_failed_analysis_raises_and_stores_nothing(self):
        failed = SkillGapReport(student_id=42, student_skills=["java"], top_matched_jobs=[], all_missing_skills=[],
                                analysis_status="ERROR", error_message="connection refused")
        store = mock.Mock()
        with self.assertRaises(RuntimeError):
            analysis_handler.analyse_and_store(self.event(), lambda **_: failed, None, store)
        store.save.assert_not_called()

    def test_skills_come_from_the_database_when_the_event_has_none(self):
        seen = {}

        def analyse(student_id, student_skills):
            seen["skills"] = student_skills
            return success_report()

        analysis_handler.analyse_and_store(self.event(skills=()), analyse, lambda sid: ["sql"], mock.Mock())
        self.assertEqual(seen["skills"], ["sql"])


if __name__ == "__main__":
    unittest.main()
