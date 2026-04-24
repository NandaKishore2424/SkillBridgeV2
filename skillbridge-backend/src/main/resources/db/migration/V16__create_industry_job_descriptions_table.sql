CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS industry_job_descriptions (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(500),
    company         VARCHAR(255),
    location        VARCHAR(255),
    required_skills TEXT,
    raw_description TEXT NOT NULL,
    embedding       vector(384),
    source          VARCHAR(100) DEFAULT 'kaggle',
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_job_embedding 
ON industry_job_descriptions 
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);

CREATE OR REPLACE FUNCTION search_similar_jobs(
    query_embedding vector(384),
    match_threshold float DEFAULT 0.5,
    match_count int DEFAULT 10
)
RETURNS TABLE (
    id bigint,
    title varchar,
    company varchar,
    required_skills text,
    similarity float
)
LANGUAGE sql STABLE
AS $$
    SELECT
        id,
        title,
        company,
        required_skills,
        1 - (embedding <-> query_embedding) AS similarity
    FROM industry_job_descriptions
    WHERE 1 - (embedding <-> query_embedding) > match_threshold
    ORDER BY embedding <-> query_embedding
    LIMIT match_count;
$$;
