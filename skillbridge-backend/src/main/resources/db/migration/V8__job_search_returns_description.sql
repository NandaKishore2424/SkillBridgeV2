-- V8: search_similar_jobs also returns raw_description (2026-09-19).
--
-- required_skills is '' on all 1,500 jobs, so the skill-gap report could find
-- jobs but never a missing skill. Decision #11: the AI service extracts the
-- skills a job asks for from its description at analysis time
-- (skill_extraction.py), which needs the description back from the search.
-- The corpus itself is not modified.
--
-- DROP and CREATE, because CREATE OR REPLACE cannot change a function's
-- result columns. Ranking and similarity are exactly V4's.

DROP FUNCTION public.search_similar_jobs(vector, double precision, integer);

CREATE FUNCTION public.search_similar_jobs(query_embedding vector, match_threshold double precision DEFAULT 0.5, match_count integer DEFAULT 10)
 RETURNS TABLE(id bigint, title character varying, company character varying, required_skills text, raw_description text, similarity double precision)
 LANGUAGE sql
 STABLE
AS $function$
    SELECT
        id,
        title,
        company,
        required_skills,
        raw_description,
        1 - (embedding <=> query_embedding) AS similarity
    FROM industry_job_descriptions
    WHERE 1 - (embedding <=> query_embedding) > match_threshold
    ORDER BY embedding <=> query_embedding
    LIMIT match_count;
$function$
;
