"""
The consumer's half of the AI event contract, in every version it accepts.

Run it with the standard library, no pytest required:

    python3 -m unittest discover -s skillbridge-ai-service/tests -t skillbridge-ai-service -v

Only `jsonschema` is needed. That is deliberate: the alternative was importing
main.py, which pulls in fastapi, pika and sentence-transformers -- a model
download to assert four string literals -- and is exactly why these literals
went untested long enough to break.

The Java half is AiEventContractTest. Both check against contracts/ai-events/:
the v1 and v2 schemas, their examples, and versions.json, so neither side can
change the wire format -- or which version is on it -- on its own.
"""

import copy
import json
import sys
import unittest
from pathlib import Path

import jsonschema

SERVICE_ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = SERVICE_ROOT.parent / "contracts" / "ai-events"

sys.path.insert(0, str(SERVICE_ROOT))
import ai_event_contract as contract  # noqa: E402


def load(path):
    return json.loads((CONTRACTS / path).read_text())


def schema(version):
    return load(f"v{version}/ai-event.schema.json")


VERSIONS = load("versions.json")["eventTypes"]


class SchemasAndExamples(unittest.TestCase):
    """The artifacts both languages are tested against must themselves be valid."""

    def test_every_schema_is_a_valid_json_schema(self):
        for version in (1, 2):
            with self.subTest(version=version):
                jsonschema.Draft202012Validator.check_schema(schema(version))

    def test_every_example_validates_against_its_own_version_only(self):
        for version, other in ((1, 2), (2, 1)):
            for name in ("skill-updated.example.json", "profile-updated.example.json"):
                with self.subTest(version=version, example=name):
                    example = load(f"v{version}/{name}")
                    jsonschema.validate(example, schema(version))
                    with self.assertRaises(jsonschema.ValidationError):
                        jsonschema.validate(example, schema(other))


class VersionsAgree(unittest.TestCase):
    """
    What this service accepts, against what the backend produces.

    The one rule a version bump must never break is produced-in-accepted: the
    producer may only move to a version consumers already read. AiEventContractTest
    checks the same file from the Java side.
    """

    def test_supported_versions_are_the_accepted_ones(self):
        self.assertEqual(
            {t: tuple(v["accepted"]) for t, v in VERSIONS.items()},
            contract.SUPPORTED_SCHEMA_VERSIONS)

    def test_the_produced_version_is_accepted(self):
        for event_type, spec in VERSIONS.items():
            with self.subTest(event_type=event_type):
                self.assertIn(spec["produced"], spec["accepted"])

    def test_every_accepted_version_has_a_schema_and_a_reader(self):
        for event_type, accepted in contract.SUPPORTED_SCHEMA_VERSIONS.items():
            for version in accepted:
                with self.subTest(event_type=event_type, version=version):
                    self.assertTrue((CONTRACTS / f"v{version}" / "ai-event.schema.json").is_file())
                    if version > contract.UNENVELOPED_VERSION:
                        self.assertIn(version, contract._ENVELOPED,
                                      "an accepted version with no reader is refused at runtime")

    def test_handled_event_types_match_every_schema(self):
        handled = set(contract.HANDLED_EVENT_TYPES)
        self.assertEqual(handled, set(VERSIONS))
        self.assertEqual(handled, set(contract.SUPPORTED_SCHEMA_VERSIONS))
        for version, path in ((1, "/properties/eventType/enum"), (2, "/properties/eventType/enum")):
            with self.subTest(version=version):
                self.assertEqual(handled, set(schema(version)["properties"]["eventType"]["enum"]))

    def test_field_names_exist_in_the_schemas(self):
        v1 = schema(1)["properties"]
        for field in (contract.FIELD_EVENT_TYPE, contract.FIELD_STUDENT_ID,
                      contract.FIELD_COLLEGE_ID, contract.FIELD_METADATA):
            with self.subTest(version=1, field=field):
                self.assertIn(field, v1)
        v2 = schema(2)
        for field in (contract.FIELD_EVENT_ID, contract.FIELD_EVENT_TYPE, contract.FIELD_SCHEMA_VERSION,
                      contract.FIELD_OCCURRED_AT, contract.FIELD_COLLEGE_ID, contract.FIELD_TRACE_ID,
                      contract.FIELD_REPLAY_OF, contract.FIELD_PAYLOAD):
            with self.subTest(version=2, field=field):
                self.assertIn(field, v2["properties"])
        payload = v2["$defs"]["SkillUpdated"]["properties"]
        self.assertIn(contract.FIELD_STUDENT_ID, payload)
        self.assertIn(contract.FIELD_SKILL_ID, payload)


class ParsingEveryVersion(unittest.TestCase):
    """parse_event reads each accepted version into the same AiEvent."""

    def test_the_examples_of_both_versions_mean_the_same_thing(self):
        v1 = contract.parse_event(load("v1/skill-updated.example.json"))
        v2 = contract.parse_event(load("v2/skill-updated.example.json"))
        for event in (v1, v2):
            self.assertEqual((event.event_type, event.student_id, event.college_id, event.skill_id),
                             ("SKILL_UPDATED", 31, 1, 7))
        self.assertEqual((v1.schema_version, v2.schema_version), (1, 2))
        self.assertIsNone(v1.event_id)
        self.assertEqual(v2.event_id, "3f1c2a9e-7b64-4d0f-9a51-6c2d8e4b7a10")
        self.assertEqual(v2.trace_id, "4bf92f3577b34da6")

    def test_profile_updated_in_both_versions(self):
        for path in ("v1/profile-updated.example.json", "v2/profile-updated.example.json"):
            with self.subTest(example=path):
                event = contract.parse_event(load(path))
                self.assertEqual((event.event_type, event.student_id, event.skill_id),
                                 ("PROFILE_UPDATED", 31, None))

    def test_unknown_fields_are_ignored_not_refused(self):
        # The tolerant reader: an optional field added by the producer must not
        # break a consumer that has not been redeployed.
        body = load("v2/skill-updated.example.json")
        body["newTopLevelField"] = {"anything": True}
        body["payload"]["proficiency"] = 4
        self.assertEqual(contract.parse_event(body).skill_id, 7)

    def test_a_replay_carries_the_event_it_replays(self):
        body = load("v2/skill-updated.example.json")
        body["replayOf"] = "11111111-2222-3333-4444-555555555555"
        self.assertEqual(contract.parse_event(body).replay_of, "11111111-2222-3333-4444-555555555555")


class RefusingLoudly(unittest.TestCase):
    """What parse_event refuses, and how it says so."""

    def rejected(self, body):
        with self.assertRaises(contract.EventRejected) as caught:
            contract.parse_event(body)
        return caught.exception

    def test_a_version_this_consumer_does_not_accept_is_refused_with_the_versions_it_does(self):
        body = load("v2/skill-updated.example.json")
        body["schemaVersion"] = 3
        error = self.rejected(body)
        self.assertEqual(error.kind, contract.REJECTED_UNSUPPORTED_VERSION)
        self.assertEqual((error.event_type, error.schema_version), ("SKILL_UPDATED", 3))
        self.assertIn("unsupported schemaVersion 3 for SKILL_UPDATED", str(error))
        self.assertIn("accepts 1, 2", str(error))

    def test_an_envelope_must_carry_a_real_version(self):
        base = load("v2/skill-updated.example.json")
        for version in (1, 0, "2", 2.0, True, None):
            with self.subTest(version=version):
                body = copy.deepcopy(base)
                body["schemaVersion"] = version
                self.assertEqual(self.rejected(body).kind, contract.REJECTED_MALFORMED)
        body = copy.deepcopy(base)
        del body["schemaVersion"]
        self.assertEqual(self.rejected(body).kind, contract.REJECTED_MALFORMED)

    def test_an_unknown_event_type_is_refused_in_either_shape(self):
        v1 = load("v1/skill-updated.example.json")
        v1["eventType"] = "SKILL_DELETED"
        v2 = load("v2/skill-updated.example.json")
        v2["eventType"] = ["not", "a", "string"]
        for body in (v1, v2):
            with self.subTest(body=body["eventType"]):
                self.assertEqual(self.rejected(body).kind, contract.REJECTED_UNKNOWN_TYPE)

    def test_a_payload_that_does_not_match_its_version_is_refused(self):
        base = load("v2/skill-updated.example.json")
        for mutate in (
                lambda b: b["payload"].pop("skillId"),
                lambda b: b["payload"].update(studentId="31"),
                lambda b: b["payload"].update(studentId=True),
                lambda b: b["payload"].update(studentId=0),
                lambda b: b.update(payload=[31, 7])):
            body = copy.deepcopy(base)
            mutate(body)
            with self.subTest(payload=body["payload"]):
                self.assertEqual(self.rejected(body).kind, contract.REJECTED_MALFORMED)

    def test_a_version_1_event_without_a_student_is_refused(self):
        body = load("v1/profile-updated.example.json")
        del body["studentId"]
        self.assertEqual(self.rejected(body).kind, contract.REJECTED_MALFORMED)

    def test_a_body_that_is_not_an_object_is_refused(self):
        for body in ([1, 2], "SKILL_UPDATED", 7, None):
            with self.subTest(body=body):
                self.assertEqual(self.rejected(body).kind, contract.REJECTED_MALFORMED)


class MetadataHandling(unittest.TestCase):
    """
    The bug the version 1 contract exists because of.

    Version 1's `metadata` was typed Object in Java. It used to carry a bare Long
    for SKILL_UPDATED and an explicit null for PROFILE_UPDATED. The dispatcher did
    `payload.get("metadata", {})` then `.get("skills", [])`, and BOTH payloads
    raised AttributeError -- the message was nacked with requeue=False and, with
    no dead-letter queue, discarded. Every AI event published before 2026-09-13
    went that way. Version 1 is still accepted, so the guard stays.
    """

    def test_an_explicit_null_is_not_saved_by_a_default(self):
        self.assertIsNone({"metadata": None}.get("metadata", {}))
        self.assertEqual(contract.metadata_of({"metadata": None}), {})

    def test_a_scalar_metadata_does_not_crash_the_parser(self):
        self.assertEqual(contract.metadata_of({"metadata": 5}), {})
        body = load("v1/skill-updated.example.json")
        body["metadata"] = 7
        self.assertIsNone(contract.parse_event(body).skill_id)

    def test_the_version_1_schema_rejects_a_scalar_metadata(self):
        broken = {"eventType": "SKILL_UPDATED", "studentId": 31, "collegeId": 1, "metadata": 7}
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(broken, schema(1))


if __name__ == "__main__":
    unittest.main()
