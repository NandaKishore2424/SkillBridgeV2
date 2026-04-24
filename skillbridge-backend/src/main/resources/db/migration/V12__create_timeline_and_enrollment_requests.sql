-- Migration V12: Create missing tables (batch_timeline_sessions, enrollment_requests)
-- Note: syllabus_modules and syllabus_topics already exist in database

-- ============================================================================
-- 1. Batch Timeline Sessions Table
-- ============================================================================
CREATE TABLE batch_timeline_sessions (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    session_number INTEGER NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    topic_id BIGINT REFERENCES syllabus_topics(id) ON DELETE SET NULL,
    planned_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_batch_session_number UNIQUE(batch_id, session_number)
);

CREATE INDEX idx_sessions_batch ON batch_timeline_sessions(batch_id);
CREATE INDEX idx_sessions_topic ON batch_timeline_sessions(topic_id);
CREATE INDEX idx_sessions_date ON batch_timeline_sessions(planned_date);

COMMENT ON TABLE batch_timeline_sessions IS 'Stores timeline/schedule sessions for each batch';
COMMENT ON COLUMN batch_timeline_sessions.session_number IS 'Sequential session number within the batch';
COMMENT ON COLUMN batch_timeline_sessions.topic_id IS 'Optional link to syllabus topic being covered';
COMMENT ON COLUMN batch_timeline_sessions.planned_date IS 'Optional planned date for the session';

-- ============================================================================
-- 2. Enrollment Requests Table
-- ============================================================================
CREATE TABLE enrollment_requests (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    student_id BIGINT NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    trainer_id BIGINT NOT NULL REFERENCES trainers(id) ON DELETE CASCADE,
    request_type VARCHAR(20) NOT NULL CHECK (request_type IN ('ADD', 'REMOVE')),
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reason TEXT,
    reviewed_by BIGINT REFERENCES users(id),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_requests_batch ON enrollment_requests(batch_id);
CREATE INDEX idx_requests_status ON enrollment_requests(status);
CREATE INDEX idx_requests_trainer ON enrollment_requests(trainer_id);
CREATE INDEX idx_requests_student ON enrollment_requests(student_id);

COMMENT ON TABLE enrollment_requests IS 'Stores trainer requests to add/remove students from batches';
COMMENT ON COLUMN enrollment_requests.request_type IS 'Type of request: ADD or REMOVE';
COMMENT ON COLUMN enrollment_requests.status IS 'Status: PENDING, APPROVED, or REJECTED';
COMMENT ON COLUMN enrollment_requests.reviewed_by IS 'Admin user who reviewed the request';
