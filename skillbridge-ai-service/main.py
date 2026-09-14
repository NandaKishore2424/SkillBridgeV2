"""
main.py — FastAPI Application Entry Point
==========================================
Senior Engineering Note:
  This file should be as thin as possible. It wires the pieces together:
  - Lifecycle events (startup/shutdown) initialize shared resources ONCE
  - RabbitMQ consumer runs in a background daemon thread (non-blocking)
  - REST endpoints expose AI capabilities for direct HTTP calls (useful for testing)
  - All real logic lives in the domain modules (skill_analyzer, embedder, database)

  This is the Dependency Injection / Separation of Concerns principle:
  main.py ORCHESTRATES but does not IMPLEMENT.
"""

import pika
import json
import threading
import dataclasses
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

# Our modular components
import config
import database
import embedder
from skill_analyzer import analyze_skill_gap
import ai_event_contract as contract
from resilient_consumer import Outcome, ResilientConsumer


# ─── Lifecycle Management ──────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Modern FastAPI lifespan manager (replaces deprecated @app.on_event).
    Everything before `yield` runs at startup; everything after runs at shutdown.
    """
    print("=" * 60)
    print("  SkillBridge AI Engine — Starting Up")
    print("=" * 60)

    # 1. Initialize the DB connection pool FIRST (other modules may need it)
    database.initialize_pool()

    # 2. Load the AI embedding model into RAM (takes a few seconds)
    embedder.initialize_embedder()

    # 3. Start the RabbitMQ consumer in a background thread. It reconnects on its
    #    own for as long as the service runs; /health reports whether it is up.
    global _consumer
    _consumer = ResilientConsumer(config.RABBITMQ_QUEUE, _handle_delivery, _connect)
    consumer_thread = threading.Thread(target=_consumer.run_forever, name="amqp-consumer", daemon=True)
    consumer_thread.start()
    print("[AMQP] RabbitMQ consumer thread started")

    print("=" * 60)
    print("  ✓ SkillBridge AI Engine is READY")
    print("=" * 60)

    yield  # FastAPI serves requests here

    # Shutdown, in order: stop taking messages and let the one in flight finish,
    # THEN close the pool it may be using. The reverse order fails the in-flight
    # analysis with a closed-pool error at exactly the moment of a deploy.
    print("[SHUTDOWN] Stopping the AMQP consumer...")
    _consumer.stop()
    consumer_thread.join(timeout=15)
    if consumer_thread.is_alive():
        print("[SHUTDOWN] ⚠ Consumer did not stop within 15s; continuing shutdown")
    print("[SHUTDOWN] Closing database connection pool...")
    database.close_pool()
    print("[SHUTDOWN] ✓ Shutdown complete")


# ─── FastAPI App ───────────────────────────────────────────────────────────────

app = FastAPI(
    title="SkillBridge AI Engine",
    description="RAG-powered skill gap analysis microservice",
    version="2.0.0",
    lifespan=lifespan
)


# ─── RabbitMQ Consumer ────────────────────────────────────────────────────────

def _process_rabbitmq_message(payload: dict) -> None:
    """
    Route incoming Java events to the correct AI handler.
    
    This is the dispatcher — it reads the `eventType` field from the Java 
    AIEvent record and calls the appropriate function.
    """
    event_type = payload.get(contract.FIELD_EVENT_TYPE, "UNKNOWN")
    student_id = payload.get(contract.FIELD_STUDENT_ID)
    # NOT payload.get("metadata", {}): a default applies only to a MISSING key,
    # so an explicit null came back as None and .get() below raised
    # AttributeError. See ai_event_contract.metadata_of.
    metadata = contract.metadata_of(payload)

    print(f"[AMQP] Processing event: type={event_type}, studentId={student_id}")

    if event_type in contract.HANDLED_EVENT_TYPES:
        # Extract the student's skills from the metadata sent by Java
        # Java's AIEventPublisher puts skillId/skillName in metadata
        student_skills = metadata.get("skills", [])

        # If skills aren't in metadata directly, use a fallback skill list
        # (In production Phase 3, Java will send the full skill list)
        if not student_skills and student_id:
            student_skills = _fetch_student_skills_from_db(student_id)

        if student_id and student_skills:
            report = analyze_skill_gap(student_id=student_id, student_skills=student_skills)
            _log_report_summary(report)
        else:
            print(f"[AMQP] ⚠ Cannot analyze — no student skills available in event payload.")
    else:
        print(f"[AMQP] ⚠ Unknown event type '{event_type}' — no handler registered. Skipping.")


def _fetch_student_skills_from_db(student_id: int) -> list[str]:
    """
    Fallback: fetch the student's skill names from Supabase directly.
    This is used when Java doesn't include the skill list in the event metadata.
    We query the existing student_skills + skills tables that the Java backend manages.
    """
    conn = database.get_connection()
    try:
        cursor = conn.cursor()
        cursor.execute("""
            SELECT s.name 
            FROM student_skills ss
            JOIN skills s ON ss.skill_id = s.id
            WHERE ss.student_id = %s
        """, (student_id,))
        rows = cursor.fetchall()
        cursor.close()
        skills = [row[0] for row in rows]
        print(f"[DB] Fetched {len(skills)} skills for student_id={student_id}: {skills}")
        return skills
    except Exception as e:
        print(f"[DB] ⚠ Could not fetch skills for student {student_id}: {e}")
        return []
    finally:
        database.return_connection(conn)


def _log_report_summary(report) -> None:
    """Print a clean, readable summary of the analysis result to the console."""
    print("\n" + "─" * 50)
    print(f"  SKILL GAP REPORT — Student ID: {report.student_id}")
    print(f"  Student Skills: {report.student_skills}")
    print(f"  Status: {report.analysis_status}")
    if report.top_matched_jobs:
        print(f"  Top {len(report.top_matched_jobs)} Matched Jobs:")
        for i, job in enumerate(report.top_matched_jobs, 1):
            print(f"    {i}. {job.title} @ {job.company[:30]} (score: {job.similarity_score})")
            if job.missing_skills:
                print(f"       Missing: {job.missing_skills[:3]}")
    if report.all_missing_skills:
        print(f"  All Skill Gaps: {report.all_missing_skills[:8]}")
    print("─" * 50 + "\n")


#: The running consumer, so /health can ask it the truth. Set in lifespan.
_consumer: ResilientConsumer | None = None


def _handle_delivery(body: bytes, properties) -> Outcome:
    """
    Decide what one delivery deserves. ResilientConsumer does the acking.

    Unparseable and unknown messages are DISCARDed: they can never succeed, and
    retrying them only holds up the messages behind. Anything that raises while
    being processed -- a database error, a model failure -- propagates, and the
    consumer treats that as RETRY. Until Phase 09 Task 2 declares a dead-letter
    exchange, RETRY is still discarded by the broker; see resilient_consumer.py.
    """
    try:
        payload = json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        print(f"[AMQP] ✗ Unparseable message body, discarding: {e}")
        return Outcome.DISCARD

    if not isinstance(payload, dict):
        print("[AMQP] ✗ Message body is not a JSON object, discarding")
        return Outcome.DISCARD

    event_type = payload.get(contract.FIELD_EVENT_TYPE)
    if event_type not in contract.HANDLED_EVENT_TYPES:
        print(f"[AMQP] ⚠ Unknown event type '{event_type}' — no handler registered. Discarding.")
        return Outcome.DISCARD

    _process_rabbitmq_message(payload)
    return Outcome.PROCESSED


def _connect():
    """
    One broker connection. ResilientConsumer calls this again after every drop.

    The old consumer made exactly one of these, under a docstring promising it
    reconnected, and a single broker restart left it dead for good while the
    service reported healthy.
    """
    parameters = pika.URLParameters(config.AMQP_URL)
    parameters.heartbeat = 30
    parameters.blocked_connection_timeout = 60
    connection = pika.BlockingConnection(parameters)
    # The backend declares this queue too; declaring it here as well means a
    # fresh broker works in either start order. Task 2 replaces this with the
    # full topology, and the arguments must then match on both sides.
    declare = connection.channel()
    declare.queue_declare(queue=config.RABBITMQ_QUEUE, durable=True)
    declare.close()
    return connection


# ─── REST API Endpoints ────────────────────────────────────────────────────────

class SkillAnalysisRequest(BaseModel):
    """Pydantic model for the HTTP request body."""
    student_id: int
    skills: list[str]


@app.get("/")
def liveness():
    """
    Liveness only: the process is up and answering HTTP. It checks nothing else.

    This used to be the health check and returned {"status": "healthy"}
    unconditionally, including while the consumer thread was dead. Use /health
    for whether the service can actually do its job.
    """
    return {"status": "alive", "service": "SkillBridge AI Engine", "version": "2.0.0"}


@app.get("/health")
def health():
    """
    Readiness: 503 unless the AMQP consumer is consuming and the database answers.

    A dead consumer is the failure this endpoint exists for. Nothing else in the
    service notices it: HTTP keeps working, and events pile up unread.
    """
    consumer_ok = _consumer is not None and _consumer.is_healthy
    db_ok = database.ping()
    status_code = 200 if (consumer_ok and db_ok) else 503
    return JSONResponse(
        status_code=status_code,
        content={
            "status": "healthy" if status_code == 200 else "degraded",
            "checks": {
                "amqp_consumer": "up" if consumer_ok else "down",
                "database": "up" if db_ok else "down",
            },
        },
    )


@app.post("/api/analyze-skills")
def analyze_skills(request: SkillAnalysisRequest):
    """
    REST endpoint to trigger skill gap analysis directly via HTTP.
    
    This is incredibly useful for:
    - Testing without needing to fire a RabbitMQ event
    - Future integration with other services
    - Quick debugging via curl or Postman
    
    Example request body:
    {
        "student_id": 5,
        "skills": ["Python", "SQL", "Pandas", "Tableau"]
    }
    """
    if not request.skills:
        raise HTTPException(status_code=400, detail="Skills list cannot be empty")

    report = analyze_skill_gap(
        student_id=request.student_id,
        student_skills=request.skills
    )

    # Convert dataclasses to dict for JSON serialization
    return JSONResponse(content={
        "student_id": report.student_id,
        "student_skills": report.student_skills,
        "analysis_status": report.analysis_status,
        "top_matched_jobs": [
            {
                "title": job.title,
                "company": job.company,
                "similarity_score": job.similarity_score,
                "matched_keywords": job.matched_keywords,
                "missing_skills": job.missing_skills
            }
            for job in report.top_matched_jobs
        ],
        "all_missing_skills": report.all_missing_skills,
        "error_message": report.error_message
    })
