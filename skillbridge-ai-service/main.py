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

    # 3. Start the RabbitMQ consumer in a background thread so it doesn't
    #    block FastAPI from serving HTTP requests
    consumer_thread = threading.Thread(target=_start_rabbitmq_consumer, daemon=True)
    consumer_thread.start()
    print("[AMQP] RabbitMQ consumer thread started")

    print("=" * 60)
    print("  ✓ SkillBridge AI Engine is READY")
    print("=" * 60)

    yield  # FastAPI serves requests here

    # Shutdown: clean up resources gracefully
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
    event_type = payload.get("eventType", "UNKNOWN")
    student_id = payload.get("studentId")
    metadata = payload.get("metadata", {})

    print(f"[AMQP] Processing event: type={event_type}, studentId={student_id}")

    if event_type in ("SKILL_UPDATED", "PROFILE_UPDATED"):
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


def _rabbitmq_callback(ch, method, properties, body):
    """
    Raw RabbitMQ callback — deserialize the message and dispatch for processing.
    Wrapped in try/except so one bad message NEVER kills the consumer thread.
    """
    try:
        payload = json.loads(body)
        _process_rabbitmq_message(payload)
        # Acknowledge the message only after successful processing
        ch.basic_ack(delivery_tag=method.delivery_tag)
    except json.JSONDecodeError as e:
        print(f"[AMQP] ✗ Invalid JSON in message body: {e}")
        ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
    except Exception as e:
        print(f"[AMQP] ✗ Unexpected error processing message: {e}")
        ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)


def _start_rabbitmq_consumer() -> None:
    """
    Blocking RabbitMQ consumer loop.
    Runs in a background daemon thread so it doesn't block FastAPI.
    Auto-reconnects on connection drops using basic_consume with manual acks.
    """
    parameters = pika.URLParameters(config.AMQP_URL)
    parameters.heartbeat = 60  # Keep the connection alive
    
    connection = pika.BlockingConnection(parameters)
    channel = connection.channel()
    channel.queue_declare(queue=config.RABBITMQ_QUEUE, durable=True)
    
    # Process ONE message at a time (prefetch=1)
    # If we're busy with analysis, don't take more from the queue
    channel.basic_qos(prefetch_count=1)
    
    channel.basic_consume(
        queue=config.RABBITMQ_QUEUE,
        on_message_callback=_rabbitmq_callback,
        auto_ack=False  # Manual acks — we confirm only after successful processing
    )
    
    print(f"[AMQP] ✓ Listening on queue: '{config.RABBITMQ_QUEUE}'")
    channel.start_consuming()  # Blocks forever in the background thread


# ─── REST API Endpoints ────────────────────────────────────────────────────────

class SkillAnalysisRequest(BaseModel):
    """Pydantic model for the HTTP request body."""
    student_id: int
    skills: list[str]


@app.get("/")
def health_check():
    """Health check endpoint. Used by monitoring tools and Docker."""
    return {
        "status": "healthy",
        "service": "SkillBridge AI Engine",
        "version": "2.0.0"
    }


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
