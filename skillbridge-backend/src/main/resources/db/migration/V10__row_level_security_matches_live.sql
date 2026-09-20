-- V10: row-level security on the job corpus, as the live database has it (2026-09-20).
--
-- The restored database has RLS enabled on industry_job_descriptions,
-- dead_letter_events and processed_events; V1, generated from the capture,
-- carried only the latter two. So a database built from the migrations differed
-- from the one the application actually runs on, in a flag no fingerprint
-- compared -- and the difference is invisible until something connects as a
-- role that is not the tables' owner.
--
-- What RLS does here, stated plainly: all three tables have it enabled and no
-- policies. The application is the tables' owner and an owner bypasses RLS
-- unless FORCE is set, so this changes nothing for it. For any other role the
-- default is "no policy, no rows": a write fails loudly, and a SELECT quietly
-- returns nothing. That is why scripts/db/ai-service-role.sql grants the AI
-- service's role explicit policies rather than relying on table grants alone,
-- and why RowLevelSecurityRulesTest pins which tables have it.

ALTER TABLE public.industry_job_descriptions ENABLE ROW LEVEL SECURITY;
