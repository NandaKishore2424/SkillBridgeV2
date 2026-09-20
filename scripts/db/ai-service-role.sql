-- A database account for the AI service, limited to what it actually uses.
--
-- The service connects with the application's own credentials today, which can
-- read every table including users, refresh_tokens and audit_log, and write all
-- of them. It needs none of that: it reads a student's skills, searches the job
-- corpus, claims events for deduplication and writes one report per student.
-- Two processes sharing one all-powerful account also means a compromise of the
-- smaller one is a compromise of everything.
--
-- Run as a superuser, against the application database:
--
--   psql -v ON_ERROR_STOP=1 -U skillbridge -d skillbridge -f scripts/db/ai-service-role.sql
--   ALTER ROLE skillbridge_ai PASSWORD '...';   -- not in this file, and not in git
--
-- Then point the service at it: AI_DATABASE_URL=postgresql://skillbridge_ai:...@host:5433/skillbridge
--
-- AiRolePrivilegesTest (skillbridge-ai-service/tests_integration) runs this file
-- against a throwaway database and checks both halves: that the service's own
-- statements work, and that reading users or writing students does not.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'skillbridge_ai') THEN
        -- No password here on purpose: it would end up in git. The operator sets
        -- it in the ALTER ROLE above.
        CREATE ROLE skillbridge_ai LOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO skillbridge_ai;

-- Read: whose skills to analyse, and the corpus to search.
GRANT SELECT ON public.students, public.student_skills, public.skills,
                public.industry_job_descriptions TO skillbridge_ai;
GRANT EXECUTE ON FUNCTION public.search_similar_jobs(vector, double precision, integer) TO skillbridge_ai;

-- Write: the deduplication claim, and the report itself.
GRANT SELECT, INSERT, UPDATE, DELETE ON public.processed_events TO skillbridge_ai;
GRANT SELECT, INSERT, UPDATE ON public.skill_gap_reports TO skillbridge_ai;

-- Row-level security is ENABLED, with no policies at all, on
-- industry_job_descriptions, processed_events and dead_letter_events (V1). The
-- application never noticed: it connects as the tables' owner, and an owner
-- bypasses RLS unless FORCE is set. Any other role meets the default, which is
-- "no policy, no rows" -- and that reads as a write refused with a clear error
-- but a SELECT that quietly returns nothing. So the two this service needs get
-- an explicit policy naming it.
CREATE POLICY ai_service_reads ON public.industry_job_descriptions
    FOR SELECT TO skillbridge_ai USING (true);
CREATE POLICY ai_service_claims ON public.processed_events
    FOR ALL TO skillbridge_ai USING (true) WITH CHECK (true);

-- Everything else is refused by omission: no grant, no access. That includes
-- users, refresh_tokens, audit_log, and writing to the job corpus.
