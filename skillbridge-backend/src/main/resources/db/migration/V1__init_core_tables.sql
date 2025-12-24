-- ============================================================================
-- Migration: V1 - Initialize Core Tables
-- Version: 1
-- Description: Creates all foundational tables for SkillBridge platform
--              - Multi-tenant colleges
--              - Authentication & authorization (users, roles, refresh tokens)
--              - User profiles (students, trainers, college admins)
--              - Skills & learning
--              - Batches & training
--              - Progress tracking
--              - Feedback system
--              - Companies & placements
-- ============================================================================

-- ============================================================================
-- 1. COLLEGES (Tenant Root)
-- ============================================================================
CREATE TABLE colleges (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(20),
    address TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX idx_colleges_code ON colleges(code);
CREATE INDEX idx_colleges_status ON colleges(status);

COMMENT ON TABLE colleges IS 'Root tenant entity - all tenant data links back to this';

-- ============================================================================
-- 2. USERS (Authentication Identity)
-- ============================================================================
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    college_id BIGINT,  -- NULL for SYSTEM_ADMIN
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_users_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE
);

CREATE INDEX idx_users_college_id ON users(college_id);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_is_active ON users(is_active);

COMMENT ON TABLE users IS 'Single authentication table for all user types';
COMMENT ON COLUMN users.college_id IS 'Tenant identifier - NULL for SYSTEM_ADMIN';

-- ============================================================================
-- 3. ROLES (RBAC)
-- ============================================================================
CREATE TABLE roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) UNIQUE NOT NULL CHECK (name IN ('SYSTEM_ADMIN', 'COLLEGE_ADMIN', 'TRAINER', 'STUDENT'))
);

-- Pre-populate roles
INSERT INTO roles (name) VALUES 
    ('SYSTEM_ADMIN'),
    ('COLLEGE_ADMIN'),
    ('TRAINER'),
    ('STUDENT');

COMMENT ON TABLE roles IS 'Available roles in the system';

-- ============================================================================
-- 4. USER ROLES (Junction Table)
-- ============================================================================
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX idx_user_roles_role_id ON user_roles(role_id);

COMMENT ON TABLE user_roles IS 'Many-to-Many relationship between users and roles';

-- ============================================================================
-- 5. REFRESH TOKENS
-- ============================================================================
CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(255) UNIQUE NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
CREATE INDEX idx_refresh_tokens_revoked ON refresh_tokens(revoked);

COMMENT ON TABLE refresh_tokens IS 'JWT refresh tokens for token rotation';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hash of refresh token (never store plain text)';

-- ============================================================================
-- 6. COLLEGE ADMINS (Profile)
-- ============================================================================
CREATE TABLE college_admins (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT UNIQUE NOT NULL,
    college_id BIGINT NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_college_admins_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_college_admins_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE
);

CREATE INDEX idx_college_admins_user_id ON college_admins(user_id);
CREATE INDEX idx_college_admins_college_id ON college_admins(college_id);

COMMENT ON TABLE college_admins IS 'Profile table for college administrators';

-- ============================================================================
-- 7. TRAINERS (Profile)
-- ============================================================================
CREATE TABLE trainers (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT UNIQUE NOT NULL,
    college_id BIGINT NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    department VARCHAR(100),
    specialization TEXT,
    bio TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_trainers_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_trainers_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE
);

CREATE INDEX idx_trainers_user_id ON trainers(user_id);
CREATE INDEX idx_trainers_college_id ON trainers(college_id);

COMMENT ON TABLE trainers IS 'Profile table for trainers/instructors';

-- ============================================================================
-- 8. STUDENTS (Profile)
-- ============================================================================
CREATE TABLE students (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT UNIQUE NOT NULL,
    college_id BIGINT NOT NULL,
    roll_number VARCHAR(50) UNIQUE NOT NULL,
    degree VARCHAR(100),
    branch VARCHAR(100),
    year INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_students_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_students_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE
);

CREATE INDEX idx_students_user_id ON students(user_id);
CREATE INDEX idx_students_college_id ON students(college_id);
CREATE INDEX idx_students_roll_number ON students(roll_number);

COMMENT ON TABLE students IS 'Profile table for students';

-- ============================================================================
-- 9. SKILLS (Global Catalog)
-- ============================================================================
CREATE TABLE skills (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    category VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX idx_skills_category ON skills(category);

-- Pre-populate sample skills
INSERT INTO skills (name, category) VALUES 
    ('Java', 'Programming'),
    ('Python', 'Programming'),
    ('JavaScript', 'Programming'),
    ('React', 'Framework'),
    ('Spring Boot', 'Framework'),
    ('Node.js', 'Framework'),
    ('PostgreSQL', 'Database'),
    ('MongoDB', 'Database'),
    ('MySQL', 'Database'),
    ('Docker', 'DevOps'),
    ('Kubernetes', 'DevOps'),
    ('AWS', 'Cloud');

COMMENT ON TABLE skills IS 'Predefined skill catalog for analytics and recommendations';

-- ============================================================================
-- 10. STUDENT SKILLS (Junction Table)
-- ============================================================================
CREATE TABLE student_skills (
    student_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    proficiency_level INTEGER NOT NULL CHECK (proficiency_level BETWEEN 1 AND 5),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (student_id, skill_id),
    CONSTRAINT fk_student_skills_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    CONSTRAINT fk_student_skills_skill FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE
);

CREATE INDEX idx_student_skills_student_id ON student_skills(student_id);
CREATE INDEX idx_student_skills_skill_id ON student_skills(skill_id);

COMMENT ON TABLE student_skills IS 'Many-to-Many relationship between students and skills with proficiency';

-- ============================================================================
-- 11. BATCHES
-- ============================================================================
CREATE TABLE batches (
    id BIGSERIAL PRIMARY KEY,
    college_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'UPCOMING' CHECK (status IN ('UPCOMING', 'OPEN', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    start_date DATE,
    end_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_batches_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE
);

CREATE INDEX idx_batches_college_id ON batches(college_id);
CREATE INDEX idx_batches_status ON batches(status);
CREATE INDEX idx_batches_college_status ON batches(college_id, status);

COMMENT ON TABLE batches IS 'Training batch/program entity';

-- ============================================================================
-- 12. BATCH TRAINERS (Junction Table)
-- ============================================================================
CREATE TABLE batch_trainers (
    batch_id BIGINT NOT NULL,
    trainer_id BIGINT NOT NULL,
    role_description VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (batch_id, trainer_id),
    CONSTRAINT fk_batch_trainers_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE,
    CONSTRAINT fk_batch_trainers_trainer FOREIGN KEY (trainer_id) REFERENCES trainers(id) ON DELETE CASCADE
);

CREATE INDEX idx_batch_trainers_batch_id ON batch_trainers(batch_id);
CREATE INDEX idx_batch_trainers_trainer_id ON batch_trainers(trainer_id);

COMMENT ON TABLE batch_trainers IS 'Many-to-Many relationship between batches and trainers';

-- ============================================================================
-- 13. BATCH ENROLLMENTS
-- ============================================================================
CREATE TABLE batch_enrollments (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'APPLIED' CHECK (status IN ('APPLIED', 'APPROVED', 'REJECTED', 'COMPLETED')),
    enrolled_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_batch_enrollments_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE,
    CONSTRAINT fk_batch_enrollments_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    CONSTRAINT uk_batch_enrollments UNIQUE (batch_id, student_id)
);

CREATE INDEX idx_batch_enrollments_batch_id ON batch_enrollments(batch_id);
CREATE INDEX idx_batch_enrollments_student_id ON batch_enrollments(student_id);
CREATE INDEX idx_batch_enrollments_status ON batch_enrollments(status);

COMMENT ON TABLE batch_enrollments IS 'Student enrollment in batches';

-- ============================================================================
-- 14. SYLLABI
-- ============================================================================
CREATE TABLE syllabi (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT UNIQUE NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_syllabi_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE
);

CREATE INDEX idx_syllabi_batch_id ON syllabi(batch_id);

COMMENT ON TABLE syllabi IS 'Syllabus/curriculum for a batch';

-- ============================================================================
-- 15. SYLLABUS TOPICS
-- ============================================================================
CREATE TABLE syllabus_topics (
    id BIGSERIAL PRIMARY KEY,
    syllabus_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    difficulty VARCHAR(20) CHECK (difficulty IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    order_index INTEGER NOT NULL,
    estimated_hours INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_syllabus_topics_syllabus FOREIGN KEY (syllabus_id) REFERENCES syllabi(id) ON DELETE CASCADE
);

CREATE INDEX idx_syllabus_topics_syllabus_id ON syllabus_topics(syllabus_id);
CREATE INDEX idx_syllabus_topics_order ON syllabus_topics(syllabus_id, order_index);

COMMENT ON TABLE syllabus_topics IS 'Individual topics within a syllabus';

-- ============================================================================
-- 16. TOPIC PROGRESS
-- ============================================================================
CREATE TABLE topic_progress (
    id BIGSERIAL PRIMARY KEY,
    student_id BIGINT NOT NULL,
    syllabus_topic_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'NEEDS_IMPROVEMENT')),
    updated_by BIGINT,
    comment TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_topic_progress_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    CONSTRAINT fk_topic_progress_topic FOREIGN KEY (syllabus_topic_id) REFERENCES syllabus_topics(id) ON DELETE CASCADE,
    CONSTRAINT fk_topic_progress_trainer FOREIGN KEY (updated_by) REFERENCES trainers(id) ON DELETE SET NULL,
    CONSTRAINT uk_topic_progress UNIQUE (student_id, syllabus_topic_id)
);

CREATE INDEX idx_topic_progress_student_id ON topic_progress(student_id);
CREATE INDEX idx_topic_progress_topic_id ON topic_progress(syllabus_topic_id);
CREATE INDEX idx_topic_progress_status ON topic_progress(status);
CREATE INDEX idx_topic_progress_student_status ON topic_progress(student_id, status);

COMMENT ON TABLE topic_progress IS 'Track student progress on individual syllabus topics';

-- ============================================================================
-- 17. FEEDBACK
-- ============================================================================
CREATE TABLE feedback (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    from_user_id BIGINT NOT NULL,
    to_user_id BIGINT NOT NULL,
    feedback_type VARCHAR(20) NOT NULL CHECK (feedback_type IN ('TRAINER_TO_STUDENT', 'STUDENT_TO_TRAINER')),
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_feedback_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE,
    CONSTRAINT fk_feedback_from_user FOREIGN KEY (from_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_feedback_to_user FOREIGN KEY (to_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_feedback_batch_id ON feedback(batch_id);
CREATE INDEX idx_feedback_from_user_id ON feedback(from_user_id);
CREATE INDEX idx_feedback_to_user_id ON feedback(to_user_id);

COMMENT ON TABLE feedback IS 'Bi-directional feedback (trainer ↔ student)';

-- ============================================================================
-- 18. COMPANIES
-- ============================================================================
CREATE TABLE companies (
    id BIGSERIAL PRIMARY KEY,
    college_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    domain VARCHAR(100),
    hiring_type VARCHAR(20) CHECK (hiring_type IN ('FULL_TIME', 'INTERNSHIP', 'BOTH')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_companies_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE
);

CREATE INDEX idx_companies_college_id ON companies(college_id);

COMMENT ON TABLE companies IS 'Companies that hire from batches';

-- ============================================================================
-- 19. BATCH COMPANIES (Junction Table)
-- ============================================================================
CREATE TABLE batch_companies (
    batch_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    hiring_count INTEGER,
    requirements TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (batch_id, company_id),
    CONSTRAINT fk_batch_companies_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE,
    CONSTRAINT fk_batch_companies_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE
);

CREATE INDEX idx_batch_companies_batch_id ON batch_companies(batch_id);
CREATE INDEX idx_batch_companies_company_id ON batch_companies(company_id);

COMMENT ON TABLE batch_companies IS 'Many-to-Many relationship between batches and companies';

-- ============================================================================
-- 20. PLACEMENTS
-- ============================================================================
CREATE TABLE placements (
    id BIGSERIAL PRIMARY KEY,
    student_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'APPLIED' CHECK (status IN ('APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED')),
    applied_date DATE NOT NULL,
    failure_reason TEXT,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_placements_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    CONSTRAINT fk_placements_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE
);

CREATE INDEX idx_placements_student_id ON placements(student_id);
CREATE INDEX idx_placements_company_id ON placements(company_id);
CREATE INDEX idx_placements_status ON placements(status);

COMMENT ON TABLE placements IS 'Track student job applications and placements';

-- ============================================================================
-- Migration Complete
-- ============================================================================
-- Total Tables Created: 20
-- - Core: colleges
-- - Auth: users, roles, user_roles, refresh_tokens
-- - Profiles: college_admins, trainers, students
-- - Skills: skills, student_skills
-- - Training: batches, batch_trainers, batch_enrollments, syllabi, syllabus_topics
-- - Progress: topic_progress
-- - Feedback: feedback
-- - Companies: companies, batch_companies, placements
-- ============================================================================
