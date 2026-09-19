-- V4: search_similar_jobs measures cosine similarity, and can use its index (2026-09-19).
--
-- The V1 definition ordered and filtered by `<->`, which in pgvector is L2
-- distance, and reported `1 - L2` as the similarity. Two things were wrong:
--
--   * idx_job_embedding is built WITH vector_cosine_ops, which serves only
--     `<=>`. With `<->` the index could never be used.
--   * `1 - L2` is not a similarity. For unit vectors L2 = sqrt(2 - 2cos), so the
--     AI service's threshold of 0.3 really demanded cos > 0.755. Measured with the
--     service's own model against the 1,500 stored jobs, a Java-backend, a data
--     and a frontend query passed 0, 1 and 0 rows. The skill-gap search returned
--     nothing, silently.
--
-- `<=>` is cosine distance, so `1 - (a <=> b)` is cosine similarity, and
-- match_threshold now means what its name says. Ranking is unchanged in
-- principle: for unit vectors L2 and cosine order identically. Only the
-- threshold's meaning moves.
--
-- V1's warning comment above the old definition stays as it was. V1 is applied
-- history, and editing it would change its checksum.
--
-- Guarded by JobSearchFunctionTest: the similarity values, the threshold, and
-- an EXPLAIN that must show idx_job_embedding.

CREATE OR REPLACE FUNCTION public.search_similar_jobs(query_embedding vector, match_threshold double precision DEFAULT 0.5, match_count integer DEFAULT 10)
 RETURNS TABLE(id bigint, title character varying, company character varying, required_skills text, similarity double precision)
 LANGUAGE sql
 STABLE
AS $function$
    SELECT
        id,
        title,
        company,
        required_skills,
        1 - (embedding <=> query_embedding) AS similarity
    FROM industry_job_descriptions
    WHERE 1 - (embedding <=> query_embedding) > match_threshold
    ORDER BY embedding <=> query_embedding
    LIMIT match_count;
$function$
;
