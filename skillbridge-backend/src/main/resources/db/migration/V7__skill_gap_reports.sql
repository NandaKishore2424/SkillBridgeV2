-- V7: the latest skill-gap analysis of each student (2026-09-19).
--
-- Until now the AI service printed its report to its own console and nothing
-- kept it, so no screen could show it. It now upserts here at the end of every
-- analysis; the backend reads it.
--
-- One row per student, the latest. Every analysis reads the student's skills
-- as they are at that moment, so whichever analysis ran last is the right one
-- to keep, even when events arrive out of order; and a redelivered event
-- simply writes the same row again.
--
-- report: the document in contracts/skill-gap-report/v1, validated against
-- that schema by both services' tests. college_id is copied from the student
-- for tenant scoping, as progress rows do.

CREATE TABLE public.skill_gap_reports (
    student_id      bigint PRIMARY KEY REFERENCES public.students(id) ON DELETE CASCADE,
    college_id      bigint NOT NULL REFERENCES public.colleges(id) ON DELETE CASCADE,
    status          character varying(20) NOT NULL CHECK (status IN ('SUCCESS', 'SKIPPED')),
    schema_version  integer NOT NULL,
    report          jsonb NOT NULL,
    source_event_id uuid,
    analyzed_at     timestamp without time zone NOT NULL
);

CREATE INDEX idx_skill_gap_reports_college_id ON public.skill_gap_reports USING btree (college_id);
