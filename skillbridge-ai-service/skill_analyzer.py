"""
skill_analyzer.py — The Core AI Brain
=======================================
Senior Engineering Note:
  This is the heart of the RAG (Retrieval-Augmented Generation) system.
  The pattern is:
    1. RETRIEVE — Use the student's skill vector to find mathematically similar
                  job descriptions from PostgreSQL using pgvector cosine similarity.
    2. ANALYZE  — Extract the required skills text from those top matches and
                  compare against what the student already has.
    3. RETURN   — A structured gap analysis report.

  No LLM call needed for this step (Phase 2). We derive gaps through simple
  set subtraction on skill keywords. Phase 3 (LangGraph) will add LLM reasoning.

  This function is called by the RabbitMQ consumer and by the REST API.
"""

import re
from dataclasses import dataclass, field
from embedder import embed_text
import job_fields
from skill_extraction import extract_skills
from database import get_connection, return_connection
from config import TOP_JOBS_TO_RETURN, SIMILARITY_THRESHOLD


@dataclass
class MatchedJob:
    """Represents one job role that semantically matches a student's skills."""
    title: str
    company: str
    similarity_score: float  # 0.0 (no match) to 1.0 (perfect match)
    matched_keywords: list[str] = field(default_factory=list)
    missing_skills: list[str] = field(default_factory=list)


@dataclass
class SkillGapReport:
    """The full analysis result returned for a student."""
    student_id: int
    student_skills: list[str]
    top_matched_jobs: list[MatchedJob]
    all_missing_skills: list[str]  # Deduplicated across all top matches
    analysis_status: str = "SUCCESS"
    error_message: str = ""


def _format_student_skills_as_text(skills: list[str]) -> str:
    """
    Convert a list of skill strings into a single searchable text.
    We phrase it like a summary to get better embedding quality from MiniLM.
    """
    skills_str = ", ".join(skills)
    return f"Professional skills: {skills_str}. Seeking data analysis and analytics roles."


def analyze_skill_gap(student_id: int, student_skills: list[str]) -> SkillGapReport:
    """
    Core RAG function: Given a student's skills, find the top matching jobs
    in PostgreSQL and calculate the skill gaps.

    Args:
        student_id: The student's database ID (for logging/tracking)
        student_skills: A list of skill strings e.g. ["Python", "SQL", "Tableau"]

    Returns:
        SkillGapReport containing matched jobs and missing skills
    """
    print(f"[ANALYZER] Starting skill gap analysis for student_id={student_id}")
    print(f"[ANALYZER] Student skills: {student_skills}")

    # ─── Step 1: Normalize skills ──────────────────────────────────────────────
    # Lowercase and deduplicate the incoming skills for consistent comparison
    normalized_student_skills = [s.lower().strip() for s in student_skills if s.strip()]
    
    if not normalized_student_skills:
        print(f"[ANALYZER] ⚠ Student {student_id} has no skills in their profile. Skipping.")
        return SkillGapReport(
            student_id=student_id,
            student_skills=[],
            top_matched_jobs=[],
            all_missing_skills=[],
            analysis_status="SKIPPED",
            error_message="Student profile has no skills listed."
        )

    # ─── Step 2: Convert student skills to a vector ────────────────────────────
    # This is the SAME model and normalization used during ETL ingestion,
    # so the vectors are comparable in the same 384-dimensional space.
    skill_text = _format_student_skills_as_text(normalized_student_skills)
    print(f"[ANALYZER] Embedding query text: '{skill_text[:80]}...'")
    
    query_vector = embed_text(skill_text)
    vector_str = "[" + ",".join(str(v) for v in query_vector) + "]"

    # ─── Step 3: Vector similarity search in PostgreSQL ───────────────────────
    # search_similar_jobs (Flyway V4) ranks by pgvector's <=> (cosine distance)
    # and returns 1 - distance, i.e. cosine similarity. The vector goes in as a
    # literal, which lets PostgreSQL inline the function and use idx_job_embedding.
    conn = get_connection()
    try:
        cursor = conn.cursor()
        cursor.execute(
            "SELECT * FROM search_similar_jobs(%s::vector, %s, %s)",
            (vector_str, SIMILARITY_THRESHOLD, TOP_JOBS_TO_RETURN)
        )
        rows = cursor.fetchall()
        cursor.close()
        print(f"[ANALYZER] Database returned {len(rows)} matching jobs above threshold {SIMILARITY_THRESHOLD}")
    except Exception as e:
        print(f"[ANALYZER] ✗ DB query failed: {e}")
        return SkillGapReport(
            student_id=student_id,
            student_skills=normalized_student_skills,
            top_matched_jobs=[],
            all_missing_skills=[],
            analysis_status="ERROR",
            error_message=str(e)
        )
    finally:
        # ALWAYS return the connection to the pool, even if an error occurred
        return_connection(conn)

    # ─── Step 4: Analyze gaps for each matched job ────────────────────────────
    matched_jobs: list[MatchedJob] = []
    all_missing_set: set[str] = set()

    for row in rows:
        # search_similar_jobs (V8): (id, title, company, required_skills, raw_description, similarity)
        job_id, title, company, required_skills_text, raw_description, similarity = row

        # What the job asks for. required_skills is empty on every stored job, so
        # this reads the description; a job that ever gets required_skills
        # filled in uses that instead (skill_extraction.py, decision #11).
        job_required_skills = extract_skills(required_skills_text or raw_description)
        
        # What does the student HAVE vs what the job WANTS?
        matched_keywords = [s for s in job_required_skills if s in normalized_student_skills]
        missing_skills = [s for s in job_required_skills if s not in normalized_student_skills]

        all_missing_set.update(missing_skills)

        matched_jobs.append(MatchedJob(
            title=title,
            # The scrape put the employer's star rating in this column, after a
            # newline. See job_fields.
            company=job_fields.employer_name(company),
            similarity_score=round(float(similarity), 3),
            matched_keywords=matched_keywords,
            missing_skills=missing_skills
        ))

    # Sort by similarity descending (best match first)
    matched_jobs.sort(key=lambda j: j.similarity_score, reverse=True)

    report = SkillGapReport(
        student_id=student_id,
        student_skills=normalized_student_skills,
        top_matched_jobs=matched_jobs,
        all_missing_skills=sorted(list(all_missing_set))
    )

    # ─── Step 5: Log a clean summary ──────────────────────────────────────────
    print(f"[ANALYZER] ✓ Analysis complete for student_id={student_id}")
    if matched_jobs:
        best = matched_jobs[0]
        print(f"[ANALYZER]   Best match: '{best.title}' @ '{best.company}' (score: {best.similarity_score})")
    if all_missing_set:
        print(f"[ANALYZER]   Top gaps: {sorted(list(all_missing_set))[:5]}")

    return report
