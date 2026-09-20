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

from fastapi import Depends, FastAPI, Header, HTTPException, Response
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel

# Our modular components
import config
import database
import embedder
from skill_analyzer import analyze_skill_gap
import analysis_handler
import report_store
import service_auth
import ai_event_contract as contract
import amqp_consumer
import dedup
import service_lifecycle
from event_dispatch import Dispatcher, EventMetrics
from resilient_consumer import ResilientConsumer

#: The name this service deduplicates under in processed_events. Renaming it
#: forgets every event already processed, so treat it like a table name.
DEDUP_CONSUMER = "skillbridge-ai-service.skill-analysis"


# ─── Lifecycle Management ──────────────────────────────────────────────────────

_reports = report_store.ReportStore(database.get_connection, database.return_connection)


def _open_resources() -> None:
    print("=" * 60)
    print("  SkillBridge AI Engine — Starting Up")
    print("=" * 60)
    # The pool first: the handler needs it. Then the model, which takes seconds.
    database.initialize_pool()
    embedder.initialize_embedder()


def _make_consumer() -> ResilientConsumer:
    """
    The analysis-queue consumer, set where /health can ask it the truth.

    Every delivery is claimed in processed_events first, so a duplicate -- and
    at-least-once delivery produces them -- is acked, not re-analysed.
    """
    global _consumer
    handler = dedup.deduplicating(
        _dispatcher,
        dedup.ProcessedEventStore(database.get_connection, database.return_connection),
        DEDUP_CONSUMER,
        lease_seconds=dedup.LEASE_SECONDS,
    )
    _consumer = amqp_consumer.build(config.AMQP_URL, handler)
    return _consumer


# Startup and shutdown, in order: service_lifecycle.py says why the order matters.
# SIGTERM reaches the shutdown half through uvicorn; tests_broker/ proves it.
lifespan = service_lifecycle.lifespan_for(_open_resources, _make_consumer, database.close_pool)


# ─── FastAPI App ───────────────────────────────────────────────────────────────

app = FastAPI(
    title="SkillBridge AI Engine",
    description="RAG-powered skill gap analysis microservice",
    version="2.0.0",
    lifespan=lifespan
)


# ─── RabbitMQ Consumer ────────────────────────────────────────────────────────

def _analyse(event: contract.AiEvent) -> None:
    """
    Re-runs the skill-gap analysis for the student an event is about.

    The same work for SKILL_UPDATED and PROFILE_UPDATED, in any accepted schema
    version: event_dispatch has already turned the message into an AiEvent.
    """
    print(f"[AMQP] Processing {event.event_type} v{event.schema_version} "
          f"for studentId={event.student_id} (event {event.event_id or 'unenveloped'})")

    stored = analysis_handler.analyse_and_store(
        event, analyze_skill_gap, _fetch_student_skills_from_db, _reports, _log_report_summary)
    if not stored:
        print(f"[AMQP] Student {event.student_id} no longer exists; report not stored")


def _fetch_student_skills_from_db(student_id: int) -> list[str]:
    """
    The student's skill names, from the tables the Java backend manages.

    A database error propagates. It used to be caught here and turned into "no
    skills", which acknowledged the event as processed and skipped the analysis
    for good -- a transient failure recorded as success, so the retry tiers and
    the DLQ never saw it. Now the consumer retries it, then dead-letters it.
    """
    conn = database.get_connection()
    try:
        with conn.cursor() as cursor:
            cursor.execute("""
                SELECT s.name
                FROM student_skills ss
                JOIN skills s ON ss.skill_id = s.id
                WHERE ss.student_id = %s
            """, (student_id,))
            skills = [row[0] for row in cursor.fetchall()]
        print(f"[DB] Fetched {len(skills)} skills for student_id={student_id}: {skills}")
        return skills
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
            # company is nullable in industry_job_descriptions; slicing None failed the event.
            print(f"    {i}. {job.title} @ {(job.company or '-')[:30]} (score: {job.similarity_score})")
            if job.missing_skills:
                print(f"       Missing: {job.missing_skills[:3]}")
    if report.all_missing_skills:
        print(f"  All Skill Gaps: {report.all_missing_skills[:8]}")
    print("─" * 50 + "\n")


#: The running consumer, so /health can ask it the truth. Set in lifespan.
_consumer: ResilientConsumer | None = None

#: Events consumed, by type, schema version and outcome; served at /metrics.
_metrics = EventMetrics()

#: What one delivery gets: decoded, version-checked, counted, then analysed.
#: Refusals (unparseable, unknown type, unsupported version) raise
#: PermanentFailure and reach the DLQ with the reason; anything _analyse raises
#: is retried on the 5s/30s/5m tiers, then dead-lettered with the last error.
_dispatcher = Dispatcher(
    {contract.EVENT_SKILL_UPDATED: _analyse, contract.EVENT_PROFILE_UPDATED: _analyse},
    _metrics,
)


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


def require_service_token(x_service_token: str = Header(default=None, alias=service_auth.HEADER)):
    """
    The guard on everything but the probes. See service_auth: no token
    configured means 503, not an open endpoint.
    """
    try:
        service_auth.check(x_service_token, service_auth.expected_token())
    except service_auth.TokenRejected as rejected:
        raise HTTPException(status_code=rejected.status_code, detail=rejected.detail) from None


@app.get("/metrics", dependencies=[Depends(require_service_token)])
def metrics():
    """
    Prometheus metrics: ai_events_consumed_total by event type, schema version and
    outcome. Whether anything still arrives in an old version is read from here.
    """
    return Response(generate_latest(_metrics.registry), media_type=CONTENT_TYPE_LATEST)


@app.post("/api/analyze-skills", dependencies=[Depends(require_service_token)])
def analyze_skills(request: SkillAnalysisRequest):
    """
    REST endpoint to trigger skill gap analysis directly via HTTP.
    
    Needs the X-Service-Token header (service_auth): the analysis costs an
    embedding and a vector search per call, and its reply describes what a named
    student can and cannot do.

    Useful for testing without firing a RabbitMQ event, and for debugging with
    curl once the token is to hand.
    
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
