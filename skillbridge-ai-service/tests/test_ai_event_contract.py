"""
The consumer's half of the AIEvent contract.

Run it with the standard library, no pytest required:

    python3 -m unittest discover -s skillbridge-ai-service/tests -v

Only `jsonschema` is needed. That is deliberate: the alternative was importing
main.py, which pulls in fastapi, pika and sentence-transformers -- a model
download to assert four string literals -- and is exactly why these literals
went untested long enough to break.

The Java half is AiEventContractTest. Both validate against
contracts/ai-events/v1/ai-event.schema.json, so neither side can change the
wire format on its own.
"""

import json
import sys
import unittest
from pathlib import Path

import jsonschema

SERVICE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SERVICE_ROOT.parent
CONTRACT_DIR = REPO_ROOT / "contracts" / "ai-events" / "v1"

sys.path.insert(0, str(SERVICE_ROOT))
import ai_event_contract as contract  # noqa: E402


def load(name):
    return json.loads((CONTRACT_DIR / name).read_text())


class SchemaAndExamples(unittest.TestCase):
    """The artifact both languages are tested against must itself be valid."""

    def setUp(self):
        self.schema = load("ai-event.schema.json")

    def test_schema_is_a_valid_json_schema(self):
        jsonschema.Draft202012Validator.check_schema(self.schema)

    def test_every_example_validates(self):
        for name in ("skill-updated.example.json", "profile-updated.example.json"):
            with self.subTest(example=name):
                jsonschema.validate(load(name), self.schema)


class ConsumerAgreesWithTheSchema(unittest.TestCase):
    """
    The consumer's constants and the schema must say the same thing.

    These are the assertions that would have caught the original defect. Each
    literal below used to be written inline in main.py, where nothing compared
    it to anything.
    """

    def setUp(self):
        self.schema = load("ai-event.schema.json")

    def test_handled_event_types_match_the_schema_enum(self):
        declared = set(self.schema["properties"]["eventType"]["enum"])
        handled = set(contract.HANDLED_EVENT_TYPES)

        # A type in the schema but not handled is one the producer may legally
        # send and the consumer silently discards -- it hits the else branch,
        # prints "no handler registered", and acks.
        self.assertEqual(
            declared, handled,
            "the schema and the consumer disagree about which events exist")

    def test_field_names_exist_in_the_schema(self):
        properties = self.schema["properties"]
        for field in (contract.FIELD_EVENT_TYPE, contract.FIELD_STUDENT_ID,
                      contract.FIELD_COLLEGE_ID, contract.FIELD_METADATA):
            with self.subTest(field=field):
                # Java serialises record component names, so these are
                # camelCase. Reading a name the producer does not send returns
                # None rather than raising, which is why a rename is silent.
                self.assertIn(field, properties)


class MetadataHandling(unittest.TestCase):
    """
    The bug this contract exists because of.

    Java's `metadata` is typed Object. It used to carry a bare Long for
    SKILL_UPDATED and an explicit null for PROFILE_UPDATED. The dispatcher did
    `payload.get("metadata", {})` then `.get("skills", [])`, and BOTH payloads
    raised AttributeError -- the message was nacked with requeue=False and, with
    no dead-letter queue, discarded. Every AI event published before 2026-09-13
    went that way, announced only by a print to stdout.
    """

    def test_an_explicit_null_is_not_saved_by_a_default(self):
        # The trap in one line: a default applies to a MISSING key, never to a
        # present-but-null one.
        self.assertIsNone({"metadata": None}.get("metadata", {}))
        self.assertEqual(contract.metadata_of({"metadata": None}), {})

    def test_a_scalar_metadata_does_not_crash_the_dispatcher(self):
        self.assertEqual(contract.metadata_of({"metadata": 5}), {})

    def test_a_missing_metadata_is_empty(self):
        self.assertEqual(contract.metadata_of({}), {})

    def test_an_object_is_returned_unchanged(self):
        self.assertEqual(
            contract.metadata_of({"metadata": {"skillId": 7}}), {"skillId": 7})

    def test_the_real_skill_updated_example_yields_usable_metadata(self):
        payload = load("skill-updated.example.json")
        self.assertEqual(contract.metadata_of(payload).get("skillId"), 7)

    def test_the_real_profile_updated_example_does_not_raise(self):
        payload = load("profile-updated.example.json")
        # .get("skills", []) on the result is what the dispatcher does next.
        self.assertEqual(contract.metadata_of(payload).get("skills", []), [])


class ScalarMetadataIsRejectedByTheContract(unittest.TestCase):
    """The schema, not just the consumer, forbids the shape that broke."""

    def test_a_bare_number_fails_validation(self):
        schema = load("ai-event.schema.json")
        broken = {"eventType": "SKILL_UPDATED", "studentId": 31,
                  "collegeId": 1, "metadata": 7}

        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(broken, schema)

    def test_an_unknown_event_type_fails_validation(self):
        schema = load("ai-event.schema.json")
        broken = {"eventType": "SKILL_DELETED", "studentId": 31, "collegeId": 1}

        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(broken, schema)


if __name__ == "__main__":
    unittest.main()
