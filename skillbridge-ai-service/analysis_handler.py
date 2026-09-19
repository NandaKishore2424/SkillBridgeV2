"""
What the consumer does with one analysis event, apart from the heavy parts.

main.py wires in the real analysis (embedding model, vector search), the real
skill lookup and the real store; tests pass stand-ins. Kept free of FastAPI,
LangChain and the model on purpose, so the fast test tier -- which CI runs with
only jsonschema and prometheus-client installed -- can exercise it.
"""

from __future__ import annotations

from typing import Any, Callable

import report_store


def analyse_and_store(event: Any,
                      analyse: Callable[..., Any],
                      fetch_skills: Callable[[int], list],
                      store: Any,
                      summarise: Callable[[Any], None] = lambda report: None) -> bool:
    """
    Analyses the student the event is about and stores the report.

    A student with no skills gets a SKIPPED report, which is stored too: the
    screen then says "add skills" rather than "not analysed yet".

    analyse() turns a failed vector search into an ERROR report rather than
    raising, which suits the HTTP endpoint. Here it must raise: returning would
    acknowledge the event as processed, and the retry tiers and the DLQ would
    never see the failure.

    Returns False if the student no longer exists, so there was nobody to store
    the report for.
    """
    # Version 1 could carry pre-resolved skill names; nothing ever sent them.
    skills = list(event.skills) or fetch_skills(event.student_id)
    report = analyse(student_id=event.student_id, student_skills=skills)
    summarise(report)
    if report.analysis_status == "ERROR":
        raise RuntimeError(f"skill-gap analysis failed for student {event.student_id}: {report.error_message}")
    return store.save(report_store.report_document(report), event.event_id)
