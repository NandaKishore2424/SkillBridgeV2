-- =============================================================================
-- SkillBridge V2 — full schema baseline
--
-- Captured 2026-09-13 from the live Supabase database (PostgreSQL 17.6),
-- reconstructed from pg_catalog rather than dumped, because pg_dump is not
-- available in this environment.
--
-- VERIFIED, not assumed. This file was applied to an empty PostgreSQL 17.6 and
-- the resulting catalogue compared against live, category by category:
--
--     columns      288  digest 7e3c2133...  identical
--     constraints  113  digest 0715a476...  see note below
--     indexes      117  digest 16284d3b...  identical
--     sequences     25  digest ef0a8155...  identical
--     functions      4  digest e50ad8e2...  identical
--     triggers       4  digest ad76ad2c...  identical
--
-- The constraint digest differs on the 20 CHECK constraints and ONLY on their
-- rendering. Live stores `ANY ((ARRAY['X'::character varying, ...])::text[])`;
-- Postgres re-renders the same parsed expression after a round trip as
-- `ANY (ARRAY[('X'::character varying)::text, ...])`, distributing the cast over
-- the elements. Primary keys (30), unique (14) and foreign keys (49) match byte
-- for byte. With the cast rendering normalised, the checks match too, both sides
-- digesting to 69bc3f4406c49877d78d54e845fc9f57.
--
-- The lesson is in `scripts/verify-schema-baseline.sh`: a schema baseline cannot
-- be verified by byte equality, because Postgres does not round-trip its own
-- rendering of a cast. Compare normalised, or you ship a check that is red
-- forever and that everyone learns to ignore.
--
-- 2026-09-14: outbox_events ADDED (receipt db/schema/2026-09-14-outbox.sql) and
-- applied to live the same day. The per-category digests above describe the
-- schema BEFORE that table. Re-verified afterwards with the canonical fingerprint
-- (scripts/schema-fingerprint.sql), live and this file agreeing exactly:
--
--     31 tables, 575 catalogue objects, digest 83118cd3ed3fc1fbbea886af0badf60f
--
-- 2026-09-15: processed_events ADDED (receipt db/schema/2026-09-15-processed-events.sql)
-- and applied to live the same day. Re-verified afterwards, live and this file
-- agreeing exactly:
--
--     32 tables, 589 catalogue objects, digest 215daa33f8d95ea1bfaeacc1ac15cd0c
--
-- (digest = md5 of the fingerprint rows joined by newlines in row order; the same
-- method reproduces the 575 digest above from the previous version of this file.)
--
-- 2026-09-16: dead_letter_events ADDED (receipt db/schema/2026-09-16-dead-letter-events.sql)
-- and applied to live the same day. Re-verified afterwards, live and this file
-- agreeing exactly:
--
--     33 tables, 622 catalogue objects, digest 4a9095e3aa02771c7eb23690897fe40d
--
-- WHY THIS FILE EXISTS
--
-- Flyway was removed on 2026-09-06 and `ddl-auto: validate` became the only
-- guard against code/schema drift. That left the schema existing in exactly one
-- place: inside the live Supabase project. There was no CREATE TABLE statement
-- anywhere in this repository. Two consequences, both closed by this file:
--
--   1. Losing the Supabase project meant losing the schema. 30 tables, 4
--      triggers, 2 generated columns and 74 indexes, recoverable from nothing.
--   2. The integration tier could not run anywhere but against live, because
--      there was no way to build a matching database. That is why 64 of 169
--      tests skipped in CI.
--
-- WHAT THIS FILE IS NOT
--
-- It is not a migration and it does not replay. The dated files beside it
-- (`2026-09-06-soft-delete.sql` and friends) are receipts of individual changes
-- applied to live. This is the current state, whole. When you change the live
-- schema, apply the change, write a dated receipt, and re-capture this file.
-- `scripts/verify-schema-baseline.sh` is how to check that the two agree. It
-- needs a connection to the live database, so it is a MANUAL step and not a CI
-- gate: no test enforces it. (An earlier version of this line named a
-- `SchemaBaselineFreshnessTest` that never existed.)
--
-- Ordering is load-bearing: extensions, sequences, tables (whose defaults call
-- nextval), sequence ownership, constraints, indexes, functions, triggers.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Extensions
--
-- Only the two the application depends on are created here. pgcrypto,
-- uuid-ossp, pg_stat_statements and supabase_vault live in Supabase-managed
-- schemas, are not referenced by any application object, and a plain Postgres
-- does not need them to satisfy `ddl-auto: validate`.
--
--   pg_trgm  — gin_trgm_ops, behind every search index
--   vector   — industry_job_descriptions.embedding and its ivfflat index
-- -----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS vector  WITH SCHEMA public;


-- -----------------------------------------------------------------------------
-- 2. Sequences
--
-- Declared before the tables, because every id column defaults to nextval() on
-- one of these. Ownership is attached in section 4, once the tables exist.
-- -----------------------------------------------------------------------------
CREATE SEQUENCE public.audit_log_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.batches_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.bulk_upload_results_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.bulk_uploads_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.college_admins_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.colleges_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.companies_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.dead_letter_events_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.enrollment_requests_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.enrollments_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.feedback_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.idempotency_keys_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.industry_job_descriptions_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.outbox_events_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.placements_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.refresh_tokens_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.roles_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.skills_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.student_batch_progress_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.student_projects_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.students_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.syllabus_modules_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.syllabus_submodules_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.syllabus_topics_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.topic_progress_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.trainers_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
CREATE SEQUENCE public.users_id_seq AS bigint START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;


-- -----------------------------------------------------------------------------
-- 3. Tables
--
-- Two things here are easy to miss and are load-bearing:
--
--   * students.search_text and trainers.search_text are GENERATED ALWAYS ...
--     STORED columns, each backed by one partial GIN index in section 6. They
--     exist because the old five-column OR spanned the students/users join and
--     no index could serve it. See docs/INDEX_STRATEGY.md § 2.
--
--   * students.user_email and trainers.user_email are denormalised copies of
--     users.email, maintained by trigger (section 8). A generated column cannot
--     reach another table -- Postgres rejects a subquery in a generation
--     expression -- so email had to be copied in before it could be generated
--     over.
--
--   * flyway_schema_history is a leftover. Flyway was removed on 2026-09-06 but
--     its bookkeeping table was never dropped. It is reproduced here because
--     this file's job is to match live exactly; it is a drop candidate, not a
--     dependency. Nothing maps it and nothing reads it.
-- -----------------------------------------------------------------------------

CREATE TABLE public.audit_log (
    id bigint DEFAULT nextval('audit_log_id_seq'::regclass) NOT NULL,
    occurred_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    actor_user_id bigint,
    actor_email character varying(255),
    college_id bigint,
    action character varying(100) NOT NULL,
    resource_type character varying(100),
    resource_id character varying(100),
    outcome character varying(20) NOT NULL,
    ip_address character varying(45),
    user_agent character varying(500),
    metadata jsonb,
    trace_id character varying(64)
);

CREATE TABLE public.batch_companies (
    batch_id bigint NOT NULL,
    company_id bigint NOT NULL,
    hiring_count integer,
    requirements text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE public.batch_trainers (
    batch_id bigint NOT NULL,
    trainer_id bigint NOT NULL,
    role_description character varying(255),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE public.batches (
    id bigint DEFAULT nextval('batches_id_seq'::regclass) NOT NULL,
    college_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    status character varying(20) DEFAULT 'UPCOMING'::character varying NOT NULL,
    start_date date,
    end_date date,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    capacity integer,
    deleted_at timestamp without time zone,
    version bigint DEFAULT 0 NOT NULL,
    deleted_by bigint
);

CREATE TABLE public.bulk_upload_results (
    id bigint DEFAULT nextval('bulk_upload_results_id_seq'::regclass) NOT NULL,
    bulk_upload_id bigint NOT NULL,
    row_number integer NOT NULL,
    status character varying(20) NOT NULL,
    entity_id bigint,
    error_message text,
    data text
);

CREATE TABLE public.bulk_uploads (
    id bigint DEFAULT nextval('bulk_uploads_id_seq'::regclass) NOT NULL,
    college_id bigint NOT NULL,
    uploaded_by_user_id bigint NOT NULL,
    entity_type character varying(20) NOT NULL,
    file_name character varying(255) NOT NULL,
    total_rows integer NOT NULL,
    successful_rows integer NOT NULL,
    failed_rows integer NOT NULL,
    status character varying(20) NOT NULL,
    error_report text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at timestamp without time zone
);

CREATE TABLE public.college_admins (
    id bigint DEFAULT nextval('college_admins_id_seq'::regclass) NOT NULL,
    user_id bigint NOT NULL,
    college_id bigint NOT NULL,
    full_name character varying(255) NOT NULL,
    phone character varying(20),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE public.colleges (
    id bigint DEFAULT nextval('colleges_id_seq'::regclass) NOT NULL,
    name character varying(255) NOT NULL,
    code character varying(50) NOT NULL,
    email character varying(255),
    phone character varying(20),
    address text,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp without time zone,
    deleted_by bigint
);

CREATE TABLE public.companies (
    id bigint DEFAULT nextval('companies_id_seq'::regclass) NOT NULL,
    college_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    domain character varying(100),
    hiring_type character varying(20),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp without time zone,
    deleted_by bigint
);

-- Dead letters a person can see (Phase 09). DeadLetterRecorder copies every
-- message from skillbridge.dlq here and acks it; an admin replays one (through the
-- outbox, as a new event) or discards it. payload is TEXT, not JSONB, because the
-- messages most likely to be dead-lettered are the ones that do not parse. See
-- db/schema/2026-09-16-dead-letter-events.sql.
CREATE TABLE public.dead_letter_events (
    id bigint DEFAULT nextval('dead_letter_events_id_seq'::regclass) NOT NULL,
    fingerprint character varying(64) NOT NULL,
    event_id uuid,
    event_type character varying(100),
    routing_key character varying(255),
    source_queue character varying(255),
    death_reason character varying(50) NOT NULL,
    failure_reason text,
    retry_count integer DEFAULT 0 NOT NULL,
    payload text NOT NULL,
    payload_encoding character varying(10) DEFAULT 'utf8'::character varying NOT NULL,
    payload_json jsonb,
    headers jsonb,
    failed_at timestamp with time zone NOT NULL,
    recorded_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    resolved_at timestamp with time zone,
    resolved_by bigint,
    resolution_note character varying(500),
    replay_event_id uuid
);

-- Payloads carry student ids; not reachable through the REST API's anon role.
ALTER TABLE public.dead_letter_events ENABLE ROW LEVEL SECURITY;

CREATE TABLE public.enrollment_requests (
    id bigint DEFAULT nextval('enrollment_requests_id_seq'::regclass) NOT NULL,
    batch_id bigint NOT NULL,
    student_id bigint NOT NULL,
    trainer_id bigint,
    request_type character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying,
    reason text,
    reviewed_by bigint,
    reviewed_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    source character varying(20) DEFAULT 'TRAINER_REQUEST'::character varying NOT NULL,
    decision_reason text,
    college_id bigint NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

CREATE TABLE public.enrollments (
    id bigint DEFAULT nextval('enrollments_id_seq'::regclass) NOT NULL,
    batch_id bigint NOT NULL,
    student_id bigint NOT NULL,
    enrolled_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    college_id bigint NOT NULL,
    enrolled_by bigint,
    completed_at timestamp without time zone,
    version bigint DEFAULT 0 NOT NULL
);

CREATE TABLE public.feedback (
    id bigint DEFAULT nextval('feedback_id_seq'::regclass) NOT NULL,
    batch_id bigint NOT NULL,
    from_user_id bigint NOT NULL,
    to_user_id bigint NOT NULL,
    feedback_type character varying(20) NOT NULL,
    rating integer NOT NULL,
    comment text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    category character varying(100)
);

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);

CREATE TABLE public.idempotency_keys (
    id bigint DEFAULT nextval('idempotency_keys_id_seq'::regclass) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    user_id bigint NOT NULL,
    endpoint character varying(255) NOT NULL,
    request_hash character varying(64) NOT NULL,
    response_status integer,
    response_body text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    state character varying(20) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    completed_at timestamp without time zone
);

CREATE TABLE public.industry_job_descriptions (
    id bigint DEFAULT nextval('industry_job_descriptions_id_seq'::regclass) NOT NULL,
    title character varying(500),
    company character varying(255),
    location character varying(255),
    required_skills text,
    raw_description text NOT NULL,
    embedding vector(384),
    source character varying(100) DEFAULT 'kaggle'::character varying,
    created_at timestamp without time zone DEFAULT now()
);

-- The transactional outbox (Phase 09). An event is written here in the SAME
-- transaction as the business change, so the two commit or roll back together;
-- OutboxRelay publishes it afterwards. No FAILED or IN_FLIGHT status: a failed
-- attempt stays PENDING with a later next_attempt_at, and a claimed batch is
-- protected by pushing next_attempt_at forward as a lease, so a relay that dies
-- mid-batch leaves nothing stuck.
CREATE TABLE public.outbox_events (
    id bigint DEFAULT nextval('outbox_events_id_seq'::regclass) NOT NULL,
    event_id uuid NOT NULL,
    aggregate_type character varying(100) NOT NULL,
    aggregate_id character varying(100) NOT NULL,
    event_type character varying(100) NOT NULL,
    schema_version integer DEFAULT 1 NOT NULL,
    routing_key character varying(255) NOT NULL,
    payload jsonb NOT NULL,
    headers jsonb,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    last_error text,
    next_attempt_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_at timestamp without time zone
);

CREATE TABLE public.placements (
    id bigint DEFAULT nextval('placements_id_seq'::regclass) NOT NULL,
    student_id bigint NOT NULL,
    company_id bigint NOT NULL,
    status character varying(20) DEFAULT 'APPLIED'::character varying NOT NULL,
    applied_date date NOT NULL,
    failure_reason text,
    notes text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- Consumer-side deduplication (Phase 09). One row per (consumer, event_id): the
-- AI service claims an event here before processing it, with a lease, so a
-- redelivered duplicate is acknowledged instead of processed twice. See
-- db/schema/2026-09-15-processed-events.sql and skillbridge-ai-service/dedup.py.
CREATE TABLE public.processed_events (
    consumer character varying(100) NOT NULL,
    event_id uuid NOT NULL,
    status character varying(20) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    attempts integer DEFAULT 1 NOT NULL,
    lease_until timestamp with time zone NOT NULL,
    first_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone
);

-- Not reachable through the REST API's anon/authenticated roles. NOT compared by
-- scripts/schema-fingerprint.sql, which does not read relrowsecurity.
ALTER TABLE public.processed_events ENABLE ROW LEVEL SECURITY;

CREATE TABLE public.refresh_tokens (
    id bigint DEFAULT nextval('refresh_tokens_id_seq'::regclass) NOT NULL,
    user_id bigint NOT NULL,
    token_hash character varying(255) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    revoked boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE public.roles (
    id bigint DEFAULT nextval('roles_id_seq'::regclass) NOT NULL,
    name character varying(50) NOT NULL
);

CREATE TABLE public.skills (
    id bigint DEFAULT nextval('skills_id_seq'::regclass) NOT NULL,
    name character varying(100) NOT NULL,
    category character varying(50) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE public.student_batch_progress (
    id bigint DEFAULT nextval('student_batch_progress_id_seq'::regclass) NOT NULL,
    student_id bigint NOT NULL,
    batch_id bigint NOT NULL,
    college_id bigint NOT NULL,
    topics_total integer DEFAULT 0 NOT NULL,
    topics_completed integer DEFAULT 0 NOT NULL,
    topics_in_progress integer DEFAULT 0 NOT NULL,
    topics_needs_work integer DEFAULT 0 NOT NULL,
    weighted_percent numeric(5,2) DEFAULT 0 NOT NULL,
    average_score numeric(5,2),
    last_activity_at timestamp without time zone,
    recomputed_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

CREATE TABLE public.student_projects (
    id bigint DEFAULT nextval('student_projects_id_seq'::regclass) NOT NULL,
    student_id bigint NOT NULL,
    title character varying(255) NOT NULL,
    description text,
    technologies text,
    project_url character varying(255),
    github_url character varying(255),
    start_date date,
    end_date date,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE public.student_skills (
    student_id bigint NOT NULL,
    skill_id bigint NOT NULL,
    proficiency_level integer NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE public.students (
    id bigint DEFAULT nextval('students_id_seq'::regclass) NOT NULL,
    user_id bigint NOT NULL,
    college_id bigint NOT NULL,
    roll_number character varying(50) NOT NULL,
    degree character varying(100),
    branch character varying(100),
    year integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    full_name character varying(255) NOT NULL,
    phone character varying(20),
    bio text,
    github_url character varying(255),
    portfolio_url character varying(255),
    resume_url character varying(255),
    deleted_at timestamp without time zone,
    deleted_by bigint,
    user_email character varying(255),
    search_text text GENERATED ALWAYS AS (((((((((lower((full_name)::text) || ' '::text) || lower((roll_number)::text)) || ' '::text) || lower((COALESCE(degree, ''::character varying))::text)) || ' '::text) || lower((COALESCE(branch, ''::character varying))::text)) || ' '::text) || lower((COALESCE(user_email, ''::character varying))::text))) STORED
);

CREATE TABLE public.syllabus_modules (
    id bigint DEFAULT nextval('syllabus_modules_id_seq'::regclass) NOT NULL,
    batch_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    display_order integer NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    start_date date,
    end_date date,
    college_id bigint NOT NULL
);

CREATE TABLE public.syllabus_submodules (
    id bigint DEFAULT nextval('syllabus_submodules_id_seq'::regclass) NOT NULL,
    module_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    display_order integer NOT NULL,
    start_date date,
    end_date date,
    week_number integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE public.syllabus_topics (
    id bigint DEFAULT nextval('syllabus_topics_id_seq'::regclass) NOT NULL,
    name character varying(255) NOT NULL,
    display_order integer NOT NULL,
    is_completed boolean DEFAULT false,
    completed_at timestamp without time zone,
    session_number integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    description text,
    submodule_id bigint NOT NULL
);

CREATE TABLE public.topic_progress (
    id bigint DEFAULT nextval('topic_progress_id_seq'::regclass) NOT NULL,
    student_id bigint NOT NULL,
    syllabus_topic_id bigint NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    updated_by bigint,
    comment text,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    batch_id bigint NOT NULL,
    college_id bigint NOT NULL,
    score integer,
    started_at timestamp without time zone,
    completed_at timestamp without time zone,
    version bigint DEFAULT 0 NOT NULL
);

CREATE TABLE public.trainers (
    id bigint DEFAULT nextval('trainers_id_seq'::regclass) NOT NULL,
    user_id bigint NOT NULL,
    college_id bigint NOT NULL,
    full_name character varying(255) NOT NULL,
    department character varying(100),
    specialization text,
    bio text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    phone character varying(20),
    linkedin_url character varying(255),
    years_of_experience integer,
    deleted_at timestamp without time zone,
    deleted_by bigint,
    user_email character varying(255),
    search_text text GENERATED ALWAYS AS (((((((lower((full_name)::text) || ' '::text) || lower((COALESCE(department, ''::character varying))::text)) || ' '::text) || lower(COALESCE(specialization, ''::text))) || ' '::text) || lower((COALESCE(user_email, ''::character varying))::text))) STORED
);

CREATE TABLE public.user_roles (
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE public.users (
    id bigint DEFAULT nextval('users_id_seq'::regclass) NOT NULL,
    college_id bigint,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    must_change_password boolean DEFAULT false,
    account_status character varying(20) DEFAULT 'ACTIVE'::character varying,
    invitation_sent_at timestamp without time zone,
    first_login_at timestamp without time zone,
    profile_completed boolean DEFAULT false
);


-- -----------------------------------------------------------------------------
-- 4. Sequence ownership
--
-- Attaches each sequence to the column that consumes it, so dropping the table
-- drops the sequence with it.
-- -----------------------------------------------------------------------------
ALTER SEQUENCE public.audit_log_id_seq OWNED BY public.audit_log.id;
ALTER SEQUENCE public.batches_id_seq OWNED BY public.batches.id;
ALTER SEQUENCE public.bulk_upload_results_id_seq OWNED BY public.bulk_upload_results.id;
ALTER SEQUENCE public.bulk_uploads_id_seq OWNED BY public.bulk_uploads.id;
ALTER SEQUENCE public.college_admins_id_seq OWNED BY public.college_admins.id;
ALTER SEQUENCE public.colleges_id_seq OWNED BY public.colleges.id;
ALTER SEQUENCE public.companies_id_seq OWNED BY public.companies.id;
ALTER SEQUENCE public.dead_letter_events_id_seq OWNED BY public.dead_letter_events.id;
ALTER SEQUENCE public.enrollment_requests_id_seq OWNED BY public.enrollment_requests.id;
ALTER SEQUENCE public.enrollments_id_seq OWNED BY public.enrollments.id;
ALTER SEQUENCE public.feedback_id_seq OWNED BY public.feedback.id;
ALTER SEQUENCE public.idempotency_keys_id_seq OWNED BY public.idempotency_keys.id;
ALTER SEQUENCE public.industry_job_descriptions_id_seq OWNED BY public.industry_job_descriptions.id;
ALTER SEQUENCE public.outbox_events_id_seq OWNED BY public.outbox_events.id;
ALTER SEQUENCE public.placements_id_seq OWNED BY public.placements.id;
ALTER SEQUENCE public.refresh_tokens_id_seq OWNED BY public.refresh_tokens.id;
ALTER SEQUENCE public.roles_id_seq OWNED BY public.roles.id;
ALTER SEQUENCE public.skills_id_seq OWNED BY public.skills.id;
ALTER SEQUENCE public.student_batch_progress_id_seq OWNED BY public.student_batch_progress.id;
ALTER SEQUENCE public.student_projects_id_seq OWNED BY public.student_projects.id;
ALTER SEQUENCE public.students_id_seq OWNED BY public.students.id;
ALTER SEQUENCE public.syllabus_modules_id_seq OWNED BY public.syllabus_modules.id;
ALTER SEQUENCE public.syllabus_submodules_id_seq OWNED BY public.syllabus_submodules.id;
ALTER SEQUENCE public.syllabus_topics_id_seq OWNED BY public.syllabus_topics.id;
ALTER SEQUENCE public.topic_progress_id_seq OWNED BY public.topic_progress.id;
ALTER SEQUENCE public.trainers_id_seq OWNED BY public.trainers.id;
ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;


-- -----------------------------------------------------------------------------
-- 5. Constraints
--
-- Primary keys, then unique, then check, then foreign keys. Foreign keys come
-- last so table creation order above does not have to be dependency-sorted.
--
-- Note what is NOT here, because it is the reason @Idempotent exists: batches,
-- companies and student_projects carry no unique constraint of any kind. A
-- duplicate submission creates a duplicate row and nothing in the database
-- stops it.
-- -----------------------------------------------------------------------------

-- 5a. Primary keys
ALTER TABLE public.audit_log ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);
ALTER TABLE public.batch_companies ADD CONSTRAINT batch_companies_pkey PRIMARY KEY (batch_id, company_id);
ALTER TABLE public.batch_trainers ADD CONSTRAINT batch_trainers_pkey PRIMARY KEY (batch_id, trainer_id);
ALTER TABLE public.batches ADD CONSTRAINT batches_pkey PRIMARY KEY (id);
ALTER TABLE public.bulk_upload_results ADD CONSTRAINT bulk_upload_results_pkey PRIMARY KEY (id);
ALTER TABLE public.bulk_uploads ADD CONSTRAINT bulk_uploads_pkey PRIMARY KEY (id);
ALTER TABLE public.college_admins ADD CONSTRAINT college_admins_pkey PRIMARY KEY (id);
ALTER TABLE public.colleges ADD CONSTRAINT colleges_pkey PRIMARY KEY (id);
ALTER TABLE public.companies ADD CONSTRAINT companies_pkey PRIMARY KEY (id);
ALTER TABLE public.dead_letter_events ADD CONSTRAINT dead_letter_events_pkey PRIMARY KEY (id);
ALTER TABLE public.enrollment_requests ADD CONSTRAINT enrollment_requests_pkey PRIMARY KEY (id);
ALTER TABLE public.enrollments ADD CONSTRAINT enrollments_pkey PRIMARY KEY (id);
ALTER TABLE public.feedback ADD CONSTRAINT feedback_pkey PRIMARY KEY (id);
ALTER TABLE public.flyway_schema_history ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);
ALTER TABLE public.idempotency_keys ADD CONSTRAINT idempotency_keys_pkey PRIMARY KEY (id);
ALTER TABLE public.industry_job_descriptions ADD CONSTRAINT industry_job_descriptions_pkey PRIMARY KEY (id);
ALTER TABLE public.outbox_events ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);
ALTER TABLE public.placements ADD CONSTRAINT placements_pkey PRIMARY KEY (id);
ALTER TABLE public.processed_events ADD CONSTRAINT processed_events_pkey PRIMARY KEY (consumer, event_id);
ALTER TABLE public.refresh_tokens ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);
ALTER TABLE public.roles ADD CONSTRAINT roles_pkey PRIMARY KEY (id);
ALTER TABLE public.skills ADD CONSTRAINT skills_pkey PRIMARY KEY (id);
ALTER TABLE public.student_batch_progress ADD CONSTRAINT student_batch_progress_pkey PRIMARY KEY (id);
ALTER TABLE public.student_projects ADD CONSTRAINT student_projects_pkey PRIMARY KEY (id);
ALTER TABLE public.student_skills ADD CONSTRAINT student_skills_pkey PRIMARY KEY (student_id, skill_id);
ALTER TABLE public.students ADD CONSTRAINT students_pkey PRIMARY KEY (id);
ALTER TABLE public.syllabus_modules ADD CONSTRAINT syllabus_modules_pkey PRIMARY KEY (id);
ALTER TABLE public.syllabus_submodules ADD CONSTRAINT syllabus_submodules_pkey PRIMARY KEY (id);
ALTER TABLE public.syllabus_topics ADD CONSTRAINT syllabus_topics_pkey PRIMARY KEY (id);
ALTER TABLE public.topic_progress ADD CONSTRAINT topic_progress_pkey PRIMARY KEY (id);
ALTER TABLE public.trainers ADD CONSTRAINT trainers_pkey PRIMARY KEY (id);
ALTER TABLE public.user_roles ADD CONSTRAINT user_roles_pkey PRIMARY KEY (user_id, role_id);
ALTER TABLE public.users ADD CONSTRAINT users_pkey PRIMARY KEY (id);

-- 5b. Unique constraints
ALTER TABLE public.college_admins ADD CONSTRAINT college_admins_user_id_key UNIQUE (user_id);
ALTER TABLE public.colleges ADD CONSTRAINT colleges_code_key UNIQUE (code);
ALTER TABLE public.dead_letter_events ADD CONSTRAINT uk_dead_letter_fingerprint UNIQUE (fingerprint);
ALTER TABLE public.enrollments ADD CONSTRAINT enrollments_batch_id_student_id_key UNIQUE (batch_id, student_id);
ALTER TABLE public.idempotency_keys ADD CONSTRAINT uk_idempotency_key_user UNIQUE (idempotency_key, user_id);
ALTER TABLE public.outbox_events ADD CONSTRAINT uk_outbox_event_id UNIQUE (event_id);
ALTER TABLE public.refresh_tokens ADD CONSTRAINT refresh_tokens_token_hash_key UNIQUE (token_hash);
ALTER TABLE public.roles ADD CONSTRAINT roles_name_key UNIQUE (name);
ALTER TABLE public.skills ADD CONSTRAINT skills_name_key UNIQUE (name);
ALTER TABLE public.student_batch_progress ADD CONSTRAINT uk_student_batch_progress UNIQUE (student_id, batch_id);
ALTER TABLE public.students ADD CONSTRAINT students_user_id_key UNIQUE (user_id);
ALTER TABLE public.syllabus_submodules ADD CONSTRAINT uk_module_submodule_order UNIQUE (module_id, display_order);
ALTER TABLE public.syllabus_topics ADD CONSTRAINT uk_submodule_display_order UNIQUE (submodule_id, display_order);
ALTER TABLE public.topic_progress ADD CONSTRAINT uk_topic_progress UNIQUE (student_id, syllabus_topic_id);
ALTER TABLE public.trainers ADD CONSTRAINT trainers_user_id_key UNIQUE (user_id);
ALTER TABLE public.users ADD CONSTRAINT users_email_key UNIQUE (email);

-- 5c. Check constraints
ALTER TABLE public.audit_log ADD CONSTRAINT chk_audit_outcome CHECK (((outcome)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying, 'DENIED'::character varying])::text[])));
ALTER TABLE public.batches ADD CONSTRAINT batches_status_check CHECK (((status)::text = ANY ((ARRAY['UPCOMING'::character varying, 'OPEN'::character varying, 'ACTIVE'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])));
ALTER TABLE public.batches ADD CONSTRAINT chk_batches_capacity CHECK (((capacity IS NULL) OR (capacity > 0)));
ALTER TABLE public.colleges ADD CONSTRAINT colleges_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])));
ALTER TABLE public.companies ADD CONSTRAINT companies_hiring_type_check CHECK (((hiring_type)::text = ANY ((ARRAY['FULL_TIME'::character varying, 'INTERNSHIP'::character varying, 'BOTH'::character varying])::text[])));
ALTER TABLE public.dead_letter_events ADD CONSTRAINT chk_dead_letter_encoding CHECK (((payload_encoding)::text = ANY ((ARRAY['utf8'::character varying, 'base64'::character varying])::text[])));
ALTER TABLE public.dead_letter_events ADD CONSTRAINT chk_dead_letter_json CHECK (((payload_json IS NULL) OR ((payload_encoding)::text = 'utf8'::text)));
ALTER TABLE public.dead_letter_events ADD CONSTRAINT chk_dead_letter_replayed CHECK ((((status)::text = 'REPLAYED'::text) = (replay_event_id IS NOT NULL)));
ALTER TABLE public.dead_letter_events ADD CONSTRAINT chk_dead_letter_resolved CHECK ((((status)::text = 'PENDING'::text) = (resolved_at IS NULL)));
ALTER TABLE public.dead_letter_events ADD CONSTRAINT chk_dead_letter_retry_count CHECK ((retry_count >= 0));
ALTER TABLE public.dead_letter_events ADD CONSTRAINT chk_dead_letter_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'REPLAYED'::character varying, 'DISCARDED'::character varying])::text[])));
ALTER TABLE public.enrollment_requests ADD CONSTRAINT chk_requests_source CHECK (((source)::text = ANY ((ARRAY['TRAINER_REQUEST'::character varying, 'STUDENT_APPLICATION'::character varying, 'ADMIN_DIRECT'::character varying])::text[])));
ALTER TABLE public.enrollment_requests ADD CONSTRAINT chk_requests_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'WITHDRAWN'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying])::text[])));
ALTER TABLE public.enrollment_requests ADD CONSTRAINT chk_requests_trainer_presence CHECK ((((source)::text <> 'TRAINER_REQUEST'::text) OR (trainer_id IS NOT NULL)));
ALTER TABLE public.enrollment_requests ADD CONSTRAINT enrollment_requests_request_type_check CHECK (((request_type)::text = ANY ((ARRAY['ADD'::character varying, 'REMOVE'::character varying])::text[])));
ALTER TABLE public.enrollments ADD CONSTRAINT chk_enrollments_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'COMPLETED'::character varying, 'DROPPED'::character varying])::text[])));
ALTER TABLE public.feedback ADD CONSTRAINT feedback_feedback_type_check CHECK (((feedback_type)::text = ANY ((ARRAY['TRAINER_TO_STUDENT'::character varying, 'STUDENT_TO_TRAINER'::character varying])::text[])));
ALTER TABLE public.feedback ADD CONSTRAINT feedback_rating_check CHECK (((rating >= 1) AND (rating <= 5)));
ALTER TABLE public.idempotency_keys ADD CONSTRAINT ck_idempotency_completed CHECK (((((state)::text = 'IN_PROGRESS'::text) AND (response_status IS NULL) AND (completed_at IS NULL)) OR (((state)::text = 'COMPLETED'::text) AND (response_status IS NOT NULL) AND (completed_at IS NOT NULL))));
ALTER TABLE public.idempotency_keys ADD CONSTRAINT ck_idempotency_state CHECK (((state)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'COMPLETED'::character varying])::text[])));
ALTER TABLE public.outbox_events ADD CONSTRAINT chk_outbox_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PUBLISHED'::character varying, 'DEAD'::character varying])::text[])));
ALTER TABLE public.placements ADD CONSTRAINT placements_status_check CHECK (((status)::text = ANY ((ARRAY['APPLIED'::character varying, 'INTERVIEW'::character varying, 'OFFER'::character varying, 'REJECTED'::character varying])::text[])));
ALTER TABLE public.processed_events ADD CONSTRAINT chk_processed_events_attempts CHECK ((attempts >= 1));
ALTER TABLE public.processed_events ADD CONSTRAINT chk_processed_events_completed CHECK ((((status)::text = 'DONE'::text) = (completed_at IS NOT NULL)));
ALTER TABLE public.processed_events ADD CONSTRAINT chk_processed_events_status CHECK (((status)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'DONE'::character varying])::text[])));
ALTER TABLE public.roles ADD CONSTRAINT roles_name_check CHECK (((name)::text = ANY ((ARRAY['SYSTEM_ADMIN'::character varying, 'COLLEGE_ADMIN'::character varying, 'TRAINER'::character varying, 'STUDENT'::character varying])::text[])));
ALTER TABLE public.student_batch_progress ADD CONSTRAINT chk_sbp_percent CHECK (((weighted_percent >= (0)::numeric) AND (weighted_percent <= (100)::numeric)));
ALTER TABLE public.student_skills ADD CONSTRAINT student_skills_proficiency_level_check CHECK (((proficiency_level >= 1) AND (proficiency_level <= 5)));
ALTER TABLE public.topic_progress ADD CONSTRAINT chk_progress_score CHECK (((score IS NULL) OR ((score >= 0) AND (score <= 100))));
ALTER TABLE public.topic_progress ADD CONSTRAINT topic_progress_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'NEEDS_IMPROVEMENT'::character varying])::text[])));

-- 5d. Foreign keys
ALTER TABLE public.batch_companies ADD CONSTRAINT fk_batch_companies_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE;
ALTER TABLE public.batch_companies ADD CONSTRAINT fk_batch_companies_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE;
ALTER TABLE public.batch_trainers ADD CONSTRAINT fk_batch_trainers_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE;
ALTER TABLE public.batch_trainers ADD CONSTRAINT fk_batch_trainers_trainer FOREIGN KEY (trainer_id) REFERENCES trainers(id) ON DELETE CASCADE;
ALTER TABLE public.batches ADD CONSTRAINT batches_deleted_by_fkey FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE public.batches ADD CONSTRAINT fk_batches_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE;
ALTER TABLE public.bulk_upload_results ADD CONSTRAINT fk_bulk_upload_results_upload FOREIGN KEY (bulk_upload_id) REFERENCES bulk_uploads(id) ON DELETE CASCADE;
ALTER TABLE public.bulk_uploads ADD CONSTRAINT fk_bulk_uploads_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE;
ALTER TABLE public.bulk_uploads ADD CONSTRAINT fk_bulk_uploads_user FOREIGN KEY (uploaded_by_user_id) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE public.college_admins ADD CONSTRAINT fk_college_admins_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE;
ALTER TABLE public.college_admins ADD CONSTRAINT fk_college_admins_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE public.colleges ADD CONSTRAINT colleges_deleted_by_fkey FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE public.companies ADD CONSTRAINT companies_deleted_by_fkey FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE public.companies ADD CONSTRAINT fk_companies_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE;
ALTER TABLE public.enrollment_requests ADD CONSTRAINT enrollment_requests_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE;
ALTER TABLE public.enrollment_requests ADD CONSTRAINT enrollment_requests_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES users(id);
ALTER TABLE public.enrollment_requests ADD CONSTRAINT enrollment_requests_student_id_fkey FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE;
ALTER TABLE public.enrollment_requests ADD CONSTRAINT enrollment_requests_trainer_id_fkey FOREIGN KEY (trainer_id) REFERENCES trainers(id) ON DELETE CASCADE;
ALTER TABLE public.enrollments ADD CONSTRAINT enrollments_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE;
ALTER TABLE public.enrollments ADD CONSTRAINT enrollments_enrolled_by_fkey FOREIGN KEY (enrolled_by) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE public.enrollments ADD CONSTRAINT enrollments_student_id_fkey FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE;
ALTER TABLE public.feedback ADD CONSTRAINT fk_feedback_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE;
ALTER TABLE public.feedback ADD CONSTRAINT fk_feedback_from_user FOREIGN KEY (from_user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE public.feedback ADD CONSTRAINT fk_feedback_to_user FOREIGN KEY (to_user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE public.idempotency_keys ADD CONSTRAINT idempotency_keys_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE public.placements ADD CONSTRAINT fk_placements_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE;
ALTER TABLE public.placements ADD CONSTRAINT fk_placements_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE;
ALTER TABLE public.refresh_tokens ADD CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE public.student_batch_progress ADD CONSTRAINT student_batch_progress_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE;
ALTER TABLE public.student_batch_progress ADD CONSTRAINT student_batch_progress_college_id_fkey FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE;
ALTER TABLE public.student_batch_progress ADD CONSTRAINT student_batch_progress_student_id_fkey FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE;
ALTER TABLE public.student_projects ADD CONSTRAINT fk_student_projects_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE;
ALTER TABLE public.student_skills ADD CONSTRAINT fk_student_skills_skill FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE;
ALTER TABLE public.student_skills ADD CONSTRAINT fk_student_skills_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE;
ALTER TABLE public.students ADD CONSTRAINT fk_students_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE;
ALTER TABLE public.students ADD CONSTRAINT fk_students_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE public.students ADD CONSTRAINT students_deleted_by_fkey FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE public.syllabus_modules ADD CONSTRAINT fk_modules_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE;
ALTER TABLE public.syllabus_modules ADD CONSTRAINT syllabus_modules_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE;
ALTER TABLE public.syllabus_submodules ADD CONSTRAINT syllabus_submodules_module_id_fkey FOREIGN KEY (module_id) REFERENCES syllabus_modules(id) ON DELETE CASCADE;
ALTER TABLE public.syllabus_topics ADD CONSTRAINT syllabus_topics_submodule_id_fkey FOREIGN KEY (submodule_id) REFERENCES syllabus_submodules(id) ON DELETE CASCADE;
ALTER TABLE public.topic_progress ADD CONSTRAINT fk_topic_progress_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE;
ALTER TABLE public.topic_progress ADD CONSTRAINT fk_topic_progress_trainer FOREIGN KEY (updated_by) REFERENCES trainers(id) ON DELETE SET NULL;
ALTER TABLE public.trainers ADD CONSTRAINT fk_trainers_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE;
ALTER TABLE public.trainers ADD CONSTRAINT fk_trainers_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE public.trainers ADD CONSTRAINT trainers_deleted_by_fkey FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE public.user_roles ADD CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE;
ALTER TABLE public.user_roles ADD CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE public.users ADD CONSTRAINT fk_users_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE;


-- -----------------------------------------------------------------------------
-- 6. Indexes
--
-- Constraint-backed indexes are omitted -- section 5 creates those implicitly.
-- These are the 73 explicitly created ones (117 total, less the 44 that
-- section 5's primary-key and unique constraints create implicitly). docs/INDEX_STRATEGY.md maps each to
-- the query it serves; three facts from it are worth repeating here because
-- they look like redundancy:
--
--   * idx_students_name_trgm, idx_students_roll_trgm and idx_trainers_name_trgm
--     were made redundant by the search_text work and are kept deliberately for
--     one release, so the new plan can be watched before they go.
--   * idx_users_email_trgm stays permanently: the users list searches email on
--     its own table, where a trigram index does work.
--   * The _live partial indexes are not duplicates of their unpartialled twins.
--     Four of the five are load-bearing.
-- -----------------------------------------------------------------------------
CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);
CREATE INDEX idx_audit_action ON public.audit_log USING btree (action, occurred_at DESC);
CREATE INDEX idx_audit_actor ON public.audit_log USING btree (actor_user_id, occurred_at DESC);
CREATE INDEX idx_audit_log_college_seek ON public.audit_log USING btree (college_id, occurred_at DESC, id DESC);
CREATE INDEX idx_audit_log_seek ON public.audit_log USING btree (occurred_at DESC, id DESC);
CREATE INDEX idx_batch_companies_company_id ON public.batch_companies USING btree (company_id);
CREATE INDEX idx_batch_trainers_trainer_id ON public.batch_trainers USING btree (trainer_id);
CREATE INDEX idx_batches_college_live ON public.batches USING btree (college_id, status) WHERE (deleted_at IS NULL);
CREATE INDEX idx_batches_college_status ON public.batches USING btree (college_id, status);
CREATE INDEX idx_batches_desc_trgm ON public.batches USING gin (lower(description) gin_trgm_ops);
CREATE INDEX idx_batches_name_trgm ON public.batches USING gin (lower((name)::text) gin_trgm_ops);
CREATE INDEX idx_batches_status ON public.batches USING btree (status);
CREATE INDEX idx_bulk_upload_results_status ON public.bulk_upload_results USING btree (status);
CREATE INDEX idx_bulk_upload_results_upload_id ON public.bulk_upload_results USING btree (bulk_upload_id);
CREATE INDEX idx_bulk_uploads_college_id ON public.bulk_uploads USING btree (college_id);
CREATE INDEX idx_bulk_uploads_created_at ON public.bulk_uploads USING btree (created_at DESC);
CREATE INDEX idx_bulk_uploads_status ON public.bulk_uploads USING btree (status);
CREATE INDEX idx_college_admins_college_id ON public.college_admins USING btree (college_id);
CREATE INDEX idx_colleges_live ON public.colleges USING btree (id) WHERE (deleted_at IS NULL);
CREATE INDEX idx_colleges_status ON public.colleges USING btree (status);
CREATE INDEX idx_companies_college_id ON public.companies USING btree (college_id);
CREATE INDEX idx_companies_live ON public.companies USING btree (college_id) WHERE (deleted_at IS NULL);
CREATE INDEX idx_companies_name_trgm ON public.companies USING gin (lower((name)::text) gin_trgm_ops);
CREATE INDEX idx_dead_letter_pending ON public.dead_letter_events USING btree (id DESC) WHERE ((status)::text = 'PENDING'::text);
CREATE INDEX idx_dead_letter_resolved ON public.dead_letter_events USING btree (resolved_at) WHERE ((status)::text <> 'PENDING'::text);
CREATE INDEX idx_enrollments_college ON public.enrollments USING btree (college_id, batch_id);
CREATE INDEX idx_enrollments_student_status ON public.enrollments USING btree (student_id, status);
CREATE INDEX idx_feedback_batch_id ON public.feedback USING btree (batch_id);
CREATE INDEX idx_feedback_from_user_id ON public.feedback USING btree (from_user_id);
CREATE INDEX idx_feedback_to_user_id ON public.feedback USING btree (to_user_id);
CREATE INDEX idx_idempotency_expiry ON public.idempotency_keys USING btree (expires_at);
CREATE INDEX idx_job_embedding ON public.industry_job_descriptions USING ivfflat (embedding vector_cosine_ops) WITH (lists='100');
CREATE INDEX idx_modules_college ON public.syllabus_modules USING btree (college_id, batch_id);
CREATE INDEX idx_outbox_dead ON public.outbox_events USING btree (created_at DESC) WHERE ((status)::text = 'DEAD'::text);
CREATE INDEX idx_outbox_pending ON public.outbox_events USING btree (next_attempt_at, id) WHERE ((status)::text = 'PENDING'::text);
CREATE INDEX idx_outbox_published ON public.outbox_events USING btree (published_at) WHERE ((status)::text = 'PUBLISHED'::text);
CREATE INDEX idx_placements_company_id ON public.placements USING btree (company_id);
CREATE INDEX idx_placements_status ON public.placements USING btree (status);
CREATE INDEX idx_placements_student_id ON public.placements USING btree (student_id);
CREATE INDEX idx_processed_events_done ON public.processed_events USING btree (completed_at) WHERE ((status)::text = 'DONE'::text);
CREATE INDEX idx_processed_events_in_progress ON public.processed_events USING btree (lease_until) WHERE ((status)::text = 'IN_PROGRESS'::text);
CREATE INDEX idx_progress_college ON public.topic_progress USING btree (college_id, batch_id);
CREATE INDEX idx_progress_needs_attention ON public.topic_progress USING btree (batch_id, updated_at DESC) WHERE ((status)::text = 'NEEDS_IMPROVEMENT'::text);
CREATE INDEX idx_progress_pending ON public.topic_progress USING btree (batch_id, student_id) WHERE ((status)::text = 'PENDING'::text);
CREATE INDEX idx_progress_student_batch ON public.topic_progress USING btree (student_id, batch_id);
CREATE INDEX idx_progress_topic_status ON public.topic_progress USING btree (syllabus_topic_id, status);
CREATE INDEX idx_refresh_tokens_expires_at ON public.refresh_tokens USING btree (expires_at);
CREATE INDEX idx_refresh_tokens_revoked ON public.refresh_tokens USING btree (revoked);
CREATE INDEX idx_refresh_tokens_user_id ON public.refresh_tokens USING btree (user_id);
CREATE INDEX idx_requests_batch ON public.enrollment_requests USING btree (batch_id);
CREATE INDEX idx_requests_college_status ON public.enrollment_requests USING btree (college_id, status);
CREATE INDEX idx_requests_status ON public.enrollment_requests USING btree (status);
CREATE INDEX idx_requests_student ON public.enrollment_requests USING btree (student_id);
CREATE INDEX idx_requests_trainer ON public.enrollment_requests USING btree (trainer_id);
CREATE INDEX idx_sbp_batch ON public.student_batch_progress USING btree (batch_id, weighted_percent);
CREATE INDEX idx_sbp_college ON public.student_batch_progress USING btree (college_id, batch_id);
CREATE INDEX idx_skills_category ON public.skills USING btree (category);
CREATE INDEX idx_student_projects_student_id ON public.student_projects USING btree (student_id);
CREATE INDEX idx_student_skills_skill_id ON public.student_skills USING btree (skill_id);
CREATE INDEX idx_students_college_id ON public.students USING btree (college_id);
CREATE INDEX idx_students_full_name ON public.students USING btree (full_name);
CREATE INDEX idx_students_name_trgm ON public.students USING gin (lower((full_name)::text) gin_trgm_ops);
CREATE INDEX idx_students_roll_number ON public.students USING btree (roll_number);
CREATE INDEX idx_students_roll_trgm ON public.students USING gin (lower((roll_number)::text) gin_trgm_ops);
CREATE INDEX idx_students_search_live ON public.students USING gin (search_text gin_trgm_ops) WHERE (deleted_at IS NULL);
CREATE INDEX idx_syllabus_modules_batch_id ON public.syllabus_modules USING btree (batch_id);
CREATE INDEX idx_topic_progress_status ON public.topic_progress USING btree (status);
CREATE INDEX idx_topic_progress_student_status ON public.topic_progress USING btree (student_id, status);
CREATE INDEX idx_trainers_college_id ON public.trainers USING btree (college_id);
CREATE INDEX idx_trainers_live ON public.trainers USING btree (college_id) WHERE (deleted_at IS NULL);
CREATE INDEX idx_trainers_name_trgm ON public.trainers USING gin (lower((full_name)::text) gin_trgm_ops);
CREATE INDEX idx_trainers_search_live ON public.trainers USING gin (search_text gin_trgm_ops) WHERE (deleted_at IS NULL);
CREATE INDEX idx_user_roles_role_id ON public.user_roles USING btree (role_id);
CREATE INDEX idx_users_account_status ON public.users USING btree (account_status);
CREATE INDEX idx_users_college_id ON public.users USING btree (college_id);
CREATE INDEX idx_users_email_trgm ON public.users USING gin (lower((email)::text) gin_trgm_ops);
CREATE INDEX idx_users_is_active ON public.users USING btree (is_active);
CREATE INDEX idx_users_must_change_password ON public.users USING btree (must_change_password);
CREATE UNIQUE INDEX uk_requests_one_pending_per_student_batch ON public.enrollment_requests USING btree (batch_id, student_id, request_type) WHERE ((status)::text = 'PENDING'::text);
CREATE UNIQUE INDEX uk_students_roll_live ON public.students USING btree (college_id, roll_number) WHERE (deleted_at IS NULL);


-- -----------------------------------------------------------------------------
-- 7. Functions
--
-- Four. Three maintain denormalised state that a generated column cannot reach;
-- the fourth is the RAG similarity search.
--
-- WARNING on search_similar_jobs: its ORDER BY uses `<->`, which in pgvector is
-- L2 distance, while idx_job_embedding is built WITH vector_cosine_ops, which
-- only serves `<=>`. The operator and the operator class do not match, so that
-- index cannot be used by this function and the query falls back to a
-- sequential scan. The `1 - (embedding <-> query_embedding)` arithmetic is also
-- not a cosine similarity, so `match_threshold` does not mean what its name
-- says. Reproduced here verbatim because this file records what IS, not what
-- should be. Fixing it belongs to Phase 12.
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.enforce_progress_denorm()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    SELECT m.batch_id, m.college_id
      INTO NEW.batch_id, NEW.college_id
    FROM syllabus_topics t
    JOIN syllabus_submodules sm ON t.submodule_id = sm.id
    JOIN syllabus_modules    m  ON sm.module_id  = m.id
    WHERE t.id = NEW.syllabus_topic_id;

    IF NEW.batch_id IS NULL THEN
        RAISE EXCEPTION 'Cannot derive batch_id for syllabus topic %', NEW.syllabus_topic_id;
    END IF;
    RETURN NEW;
END;
$function$
;

CREATE OR REPLACE FUNCTION public.propagate_user_email()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    UPDATE students SET user_email = NEW.email WHERE user_id = NEW.id;
    UPDATE trainers SET user_email = NEW.email WHERE user_id = NEW.id;
    RETURN NULL;
END;
$function$
;

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
        1 - (embedding <-> query_embedding) AS similarity
    FROM industry_job_descriptions
    WHERE 1 - (embedding <-> query_embedding) > match_threshold
    ORDER BY embedding <-> query_embedding
    LIMIT match_count;
$function$
;

CREATE OR REPLACE FUNCTION public.sync_user_email()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    SELECT u.email INTO NEW.user_email FROM users u WHERE u.id = NEW.user_id;
    RETURN NEW;
END;
$function$
;


-- -----------------------------------------------------------------------------
-- 8. Triggers
--
-- trg_users_email_propagate is the one that is easy to forget and the one
-- SearchTextMaintenanceTest exists to guard: without it, changing a user's
-- email leaves students.user_email stale, and search_text -- generated over
-- that column -- silently stops matching the address the search box advertises.
-- -----------------------------------------------------------------------------
CREATE TRIGGER trg_students_user_email BEFORE INSERT OR UPDATE OF user_id ON public.students FOR EACH ROW EXECUTE FUNCTION sync_user_email();
CREATE TRIGGER trg_progress_denorm BEFORE INSERT OR UPDATE OF syllabus_topic_id ON public.topic_progress FOR EACH ROW EXECUTE FUNCTION enforce_progress_denorm();
CREATE TRIGGER trg_trainers_user_email BEFORE INSERT OR UPDATE OF user_id ON public.trainers FOR EACH ROW EXECUTE FUNCTION sync_user_email();
CREATE TRIGGER trg_users_email_propagate AFTER UPDATE OF email ON public.users FOR EACH ROW WHEN (((old.email)::text IS DISTINCT FROM (new.email)::text)) EXECUTE FUNCTION propagate_user_email();
