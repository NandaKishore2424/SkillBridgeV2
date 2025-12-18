-- ============================================================================
-- Migration: V1 - Initialize Core Tables
-- Version: 1
-- Description: Creates foundational tables for SkillBridge platform
--              - colleges: Multi-tenant colleges/institutions
--              - users: User accounts with role-based access
--              - refresh_tokens: JWT refresh token storage
-- ============================================================================

-- ============================================================================
-- Table: colleges
-- Purpose: Stores college/institution information (platform-level, no tenant)
-- Multi-tenancy: This IS the tenant - no college_id needed
-- ============================================================================
CREATE TABLE colleges (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    domain VARCHAR(255) UNIQUE,  -- Optional: college domain (e.g., "mit.edu")
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index for faster lookups
CREATE INDEX idx_colleges_domain ON colleges(domain);
CREATE INDEX idx_colleges_active ON colleges(active);

-- Add comment
COMMENT ON TABLE colleges IS 'Colleges/institutions using the SkillBridge platform (tenants)';

-- ============================================================================
-- Table: users
-- Purpose: User accounts for authentication and authorization
-- Multi-tenancy: Has college_id (tenant identifier)
-- Note: college_id is NULL for SYSTEM_ADMIN, required for others
-- ============================================================================
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    college_id BIGINT,  -- NULL for SYSTEM_ADMIN, required for others
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,  -- BCrypt hashed password
    role VARCHAR(50) NOT NULL,  -- SYSTEM_ADMIN, COLLEGE_ADMIN, TRAINER, STUDENT
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Foreign key constraint
    CONSTRAINT fk_users_college
        FOREIGN KEY (college_id) REFERENCES colleges (id)
        ON DELETE SET NULL  -- If college deleted, set college_id to NULL
);

-- Indexes for performance
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_college_id ON users(college_id);
CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_active ON users(active);

-- Composite index for common queries (college + role)
CREATE INDEX idx_users_college_role ON users(college_id, role);

-- Add comments
COMMENT ON TABLE users IS 'User accounts for authentication and authorization';
COMMENT ON COLUMN users.college_id IS 'Tenant identifier - NULL for SYSTEM_ADMIN, required for others';
COMMENT ON COLUMN users.role IS 'User role: SYSTEM_ADMIN, COLLEGE_ADMIN, TRAINER, STUDENT';
COMMENT ON COLUMN users.password IS 'BCrypt hashed password (never store plain text)';

-- ============================================================================
-- Table: refresh_tokens
-- Purpose: Stores JWT refresh tokens for token rotation
-- Multi-tenancy: Indirectly tenant-scoped via user_id -> college_id
-- ============================================================================
CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(255) NOT NULL,  -- Hashed token (not plain text)
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Foreign key constraint
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE  -- If user deleted, delete their tokens
);

-- Indexes for performance
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
CREATE INDEX idx_refresh_tokens_revoked ON refresh_tokens(revoked);

-- Composite index for token validation queries
CREATE INDEX idx_refresh_tokens_hash_revoked ON refresh_tokens(token_hash, revoked);

-- Add comments
COMMENT ON TABLE refresh_tokens IS 'JWT refresh tokens for token rotation and revocation';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hash of refresh token (never store plain text)';
COMMENT ON COLUMN refresh_tokens.revoked IS 'Whether this token has been revoked (e.g., on logout or password change)';

-- ============================================================================
-- Initial Data (Optional - for development/testing)
-- ============================================================================

-- Note: We'll create a system admin user later via application code
-- This is just a placeholder to show the structure

-- ============================================================================
-- Migration Complete
-- ============================================================================

