# SkillBridge - Complete Database Schema Design

This document contains the complete, refined database schema for SkillBridge with all tables, relationships, indexes, and design decisions.

---

## Table of Contents

1. [Design Principles](#design-principles)
2. [Core Tables](#core-tables)
3. [Authentication & Authorization](#authentication--authorization)
4. [User Profiles](#user-profiles)
5. [Skills & Learning](#skills--learning)
6. [Batches & Training](#batches--training)
7. [Progress Tracking](#progress-tracking)
8. [Feedback System](#feedback-system)
9. [Companies & Placement](#companies--placement)
10. [Entity Relationship Diagram](#entity-relationship-diagram)
11. [Class Diagram](#class-diagram)
12. [Indexes & Performance](#indexes--performance)
13. [Data Types & Constraints](#data-types--constraints)

---

## Design Principles

### 1. Multi-Tenancy
- **Tenant Identifier**: `college_id` in all tenant-scoped tables
- **Isolation**: Each college's data is completely isolated
- **System Admin**: `college_id` is NULL for SYSTEM_ADMIN users

### 2. Single User Table
- **One authentication table** (`users`) for all user types
- **Role-based access** via `user_roles` junction table
- **Profile tables** (`students`, `trainers`, `college_admins`) for domain-specific data

### 3. Separation of Concerns
- **Authentication** (`users`) ≠ **Domain Profiles** (`students`, `trainers`)
- **User identity** ≠ **Student/Trainer details**
- Clean separation enables future microservice extraction

### 4. Extensibility
- Easy to add new roles
- Easy to add new profile types
- Normalized structure supports future features

---

## Core Tables

### 1. Colleges (Tenant Root)

**Purpose**: Root tenant entity. All tenant data links back to this.

```sql
CREATE TABLE colleges (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(50) UNIQUE NOT NULL,  -- Unique college code
    email VARCHAR(255),
    phone VARCHAR(20),
    address TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
```

**Fields:**
- `id`: Primary key
- `name`: College name
- `code`: Unique college identifier (e.g., "MIT", "STANFORD")
- `email`: Contact email
- `phone`: Contact phone
- `address`: Physical address
- `status`: ACTIVE or INACTIVE
- `created_at`, `updated_at`: Audit timestamps

**Relationships:**
- One-to-Many with `users`
- One-to-Many with `batches`
- One-to-Many with `companies`

---

## Authentication & Authorization

### 2. Users (Authentication Identity)

**Purpose**: Single authentication table for all user types.

```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    college_id BIGINT,  -- NULL for SYSTEM_ADMIN
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,  -- BCrypt hashed password
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_users_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE
);
```

**Fields:**
- `id`: Primary key
- `college_id`: Foreign key to colleges (NULL for SYSTEM_ADMIN)
- `email`: Unique email address (used for login)
- `password_hash`: BCrypt hashed password
- `is_active`: Account status (can be disabled)
- `created_at`, `updated_at`: Audit timestamps

**Relationships:**
- Many-to-One with `colleges`
- Many-to-Many with `roles` (via `user_roles`)
- One-to-One with `students`, `trainers`, `college_admins`

**Constraints:**
- `college_id` can be NULL (for SYSTEM_ADMIN)
- `email` must be unique across all users

---

### 3. Roles (RBAC)

**Purpose**: Define available roles in the system.

```sql
CREATE TABLE roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) UNIQUE NOT NULL CHECK (name IN ('SYSTEM_ADMIN', 'COLLEGE_ADMIN', 'TRAINER', 'STUDENT'))
);
```

**Fields:**
- `id`: Primary key
- `name`: Role name (SYSTEM_ADMIN, COLLEGE_ADMIN, TRAINER, STUDENT)

**Pre-populated Data:**
```sql
INSERT INTO roles (name) VALUES 
    ('SYSTEM_ADMIN'),
    ('COLLEGE_ADMIN'),
    ('TRAINER'),
    ('STUDENT');
```

---

### 4. User Roles (Junction Table)

**Purpose**: Many-to-Many relationship between users and roles.

```sql
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);
```

**Fields:**
- `user_id`: Foreign key to users
- `role_id`: Foreign key to roles
- `created_at`: When role was assigned

**Relationships:**
- Many-to-Many: `users` ↔ `roles`

**Note**: A user can have multiple roles (e.g., TRAINER + COLLEGE_ADMIN)

---

### 5. Refresh Tokens

**Purpose**: Store JWT refresh tokens securely.

```sql
CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(255) UNIQUE NOT NULL,  -- Hashed token (not plain text)
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
```

**Fields:**
- `id`: Primary key
- `user_id`: Foreign key to users
- `token_hash`: SHA-256 hash of refresh token
- `expires_at`: Token expiration timestamp
- `revoked`: Whether token has been revoked
- `created_at`: When token was created

**Relationships:**
- Many-to-One with `users`

**Security**: Tokens are hashed before storage (never store plain text)

---

## User Profiles

### 6. College Admins

**Purpose**: Profile table for college administrators.

```sql
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
```

**Fields:**
- `id`: Primary key
- `user_id`: Foreign key to users (one-to-one)
- `college_id`: Foreign key to colleges
- `full_name`: Admin's full name
- `phone`: Contact phone
- `created_at`, `updated_at`: Audit timestamps

**Relationships:**
- One-to-One with `users`
- Many-to-One with `colleges`

---

### 7. Trainers

**Purpose**: Profile table for trainers/instructors.

```sql
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
```

**Fields:**
- `id`: Primary key
- `user_id`: Foreign key to users (one-to-one)
- `college_id`: Foreign key to colleges
- `full_name`: Trainer's full name
- `department`: Department name
- `specialization`: Areas of expertise
- `bio`: Biography/description
- `created_at`, `updated_at`: Audit timestamps

**Relationships:**
- One-to-One with `users`
- Many-to-One with `colleges`
- Many-to-Many with `batches` (via `batch_trainers`)

---

### 8. Students

**Purpose**: Profile table for students.

```sql
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
```

**Fields:**
- `id`: Primary key
- `user_id`: Foreign key to users (one-to-one)
- `college_id`: Foreign key to colleges
- `roll_number`: Unique student roll number
- `degree`: Degree program (e.g., "B.Tech", "M.Sc")
- `branch`: Branch/specialization (e.g., "Computer Science")
- `year`: Academic year
- `created_at`, `updated_at`: Audit timestamps

**Relationships:**
- One-to-One with `users`
- Many-to-One with `colleges`
- Many-to-Many with `batches` (via `batch_enrollments`)
- Many-to-Many with `skills` (via `student_skills`)

---

## Skills & Learning

### 9. Skills (Global Catalog)

**Purpose**: Predefined skill catalog for analytics and recommendations.

```sql
CREATE TABLE skills (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    category VARCHAR(50) NOT NULL,  -- e.g., "Programming", "Database", "Framework"
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
```

**Fields:**
- `id`: Primary key
- `name`: Skill name (e.g., "Java", "React", "PostgreSQL")
- `category`: Skill category for grouping
- `created_at`: When skill was added to catalog

**Relationships:**
- Many-to-Many with `students` (via `student_skills`)

**Pre-populated Examples:**
```sql
INSERT INTO skills (name, category) VALUES 
    ('Java', 'Programming'),
    ('Python', 'Programming'),
    ('React', 'Framework'),
    ('Spring Boot', 'Framework'),
    ('PostgreSQL', 'Database'),
    ('MongoDB', 'Database');
```

---

### 10. Student Skills

**Purpose**: Many-to-Many relationship between students and skills with proficiency.

```sql
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
```

**Fields:**
- `student_id`: Foreign key to students
- `skill_id`: Foreign key to skills
- `proficiency_level`: 1 (Beginner) to 5 (Expert)
- `created_at`, `updated_at`: Audit timestamps

**Proficiency Levels:**
- 1: Beginner
- 2: Basic
- 3: Intermediate
- 4: Advanced
- 5: Expert

**Relationships:**
- Many-to-Many: `students` ↔ `skills`

---

## Batches & Training

### 11. Batches

**Purpose**: Training batch/program entity.

```sql
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
```

**Fields:**
- `id`: Primary key
- `college_id`: Foreign key to colleges
- `name`: Batch name (e.g., "Java Bootcamp 2024")
- `description`: Batch description
- `status`: UPCOMING, OPEN, ACTIVE, COMPLETED, CANCELLED
- `start_date`: Batch start date
- `end_date`: Batch end date
- `created_at`, `updated_at`: Audit timestamps

**Status Flow:**
```
UPCOMING → OPEN → ACTIVE → COMPLETED / CANCELLED
```

**Relationships:**
- Many-to-One with `colleges`
- One-to-One with `syllabi`
- Many-to-Many with `trainers` (via `batch_trainers`)
- Many-to-Many with `students` (via `batch_enrollments`)
- Many-to-Many with `companies` (via `batch_companies`)

---

### 12. Batch Trainers

**Purpose**: Many-to-Many relationship between batches and trainers.

```sql
CREATE TABLE batch_trainers (
    batch_id BIGINT NOT NULL,
    trainer_id BIGINT NOT NULL,
    role_description VARCHAR(255),  -- e.g., "Lead Trainer", "Assistant Trainer"
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (batch_id, trainer_id),
    CONSTRAINT fk_batch_trainers_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE,
    CONSTRAINT fk_batch_trainers_trainer FOREIGN KEY (trainer_id) REFERENCES trainers(id) ON DELETE CASCADE
);
```

**Fields:**
- `batch_id`: Foreign key to batches
- `trainer_id`: Foreign key to trainers
- `role_description`: Trainer's role in this batch
- `created_at`: When trainer was assigned

**Relationships:**
- Many-to-Many: `batches` ↔ `trainers`

---

### 13. Batch Enrollments

**Purpose**: Student enrollment in batches.

```sql
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
    CONSTRAINT uk_batch_enrollments UNIQUE (batch_id, student_id)  -- Student can only enroll once per batch
);
```

**Fields:**
- `id`: Primary key
- `batch_id`: Foreign key to batches
- `student_id`: Foreign key to students
- `status`: APPLIED, APPROVED, REJECTED, COMPLETED
- `enrolled_at`: When student was approved/enrolled
- `created_at`, `updated_at`: Audit timestamps

**Relationships:**
- Many-to-Many: `batches` ↔ `students`

**Constraints:**
- Unique constraint: One enrollment per student per batch

---

### 14. Syllabi

**Purpose**: Syllabus/curriculum for a batch.

```sql
CREATE TABLE syllabi (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT UNIQUE NOT NULL,  -- One syllabus per batch
    title VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_syllabi_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE
);
```

**Fields:**
- `id`: Primary key
- `batch_id`: Foreign key to batches (one-to-one)
- `title`: Syllabus title
- `description`: Syllabus description
- `created_at`, `updated_at`: Audit timestamps

**Relationships:**
- One-to-One with `batches`
- One-to-Many with `syllabus_topics`

---

### 15. Syllabus Topics

**Purpose**: Individual topics within a syllabus.

```sql
CREATE TABLE syllabus_topics (
    id BIGSERIAL PRIMARY KEY,
    syllabus_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    difficulty VARCHAR(20) CHECK (difficulty IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    order_index INTEGER NOT NULL,  -- Order within syllabus
    estimated_hours INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_syllabus_topics_syllabus FOREIGN KEY (syllabus_id) REFERENCES syllabi(id) ON DELETE CASCADE
);
```

**Fields:**
- `id`: Primary key
- `syllabus_id`: Foreign key to syllabi
- `title`: Topic title
- `description`: Topic description
- `difficulty`: BEGINNER, INTERMEDIATE, ADVANCED
- `order_index`: Order within syllabus (1, 2, 3...)
- `estimated_hours`: Estimated hours to complete
- `created_at`, `updated_at`: Audit timestamps

**Relationships:**
- Many-to-One with `syllabi`
- One-to-Many with `topic_progress`

---

## Progress Tracking

### 16. Topic Progress

**Purpose**: Track student progress on individual syllabus topics.

```sql
CREATE TABLE topic_progress (
    id BIGSERIAL PRIMARY KEY,
    student_id BIGINT NOT NULL,
    syllabus_topic_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'NEEDS_IMPROVEMENT')),
    updated_by BIGINT,  -- trainer_id who last updated
    comment TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_topic_progress_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    CONSTRAINT fk_topic_progress_topic FOREIGN KEY (syllabus_topic_id) REFERENCES syllabus_topics(id) ON DELETE CASCADE,
    CONSTRAINT fk_topic_progress_trainer FOREIGN KEY (updated_by) REFERENCES trainers(id) ON DELETE SET NULL,
    CONSTRAINT uk_topic_progress UNIQUE (student_id, syllabus_topic_id)  -- One progress record per student per topic
);
```

**Fields:**
- `id`: Primary key
- `student_id`: Foreign key to students
- `syllabus_topic_id`: Foreign key to syllabus_topics
- `status`: PENDING, IN_PROGRESS, COMPLETED, NEEDS_IMPROVEMENT
- `updated_by`: Trainer who last updated (can be NULL)
- `comment`: Trainer's comment/feedback
- `updated_at`, `created_at`: Audit timestamps

**Relationships:**
- Many-to-One with `students`
- Many-to-One with `syllabus_topics`
- Many-to-One with `trainers` (updated_by)

**Constraints:**
- Unique constraint: One progress record per student per topic

---

## Feedback System

### 17. Feedback

**Purpose**: Bi-directional feedback (trainer ↔ student).

```sql
CREATE TABLE feedback (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    from_user_id BIGINT NOT NULL,  -- User giving feedback
    to_user_id BIGINT NOT NULL,    -- User receiving feedback
    feedback_type VARCHAR(20) NOT NULL CHECK (feedback_type IN ('TRAINER_TO_STUDENT', 'STUDENT_TO_TRAINER')),
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_feedback_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE,
    CONSTRAINT fk_feedback_from_user FOREIGN KEY (from_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_feedback_to_user FOREIGN KEY (to_user_id) REFERENCES users(id) ON DELETE CASCADE
);
```

**Fields:**
- `id`: Primary key
- `batch_id`: Foreign key to batches
- `from_user_id`: User giving feedback
- `to_user_id`: User receiving feedback
- `feedback_type`: TRAINER_TO_STUDENT or STUDENT_TO_TRAINER
- `rating`: 1 to 5 stars
- `comment`: Feedback text
- `created_at`: When feedback was given

**Relationships:**
- Many-to-One with `batches`
- Many-to-One with `users` (from_user_id)
- Many-to-One with `users` (to_user_id)

---

## Companies & Placement

### 18. Companies

**Purpose**: Companies that hire from batches.

```sql
CREATE TABLE companies (
    id BIGSERIAL PRIMARY KEY,
    college_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    domain VARCHAR(100),  -- Industry domain
    hiring_type VARCHAR(20) CHECK (hiring_type IN ('FULL_TIME', 'INTERNSHIP', 'BOTH')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_companies_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE
);
```

**Fields:**
- `id`: Primary key
- `college_id`: Foreign key to colleges
- `name`: Company name
- `domain`: Industry domain (e.g., "Technology", "Finance")
- `hiring_type`: FULL_TIME, INTERNSHIP, BOTH
- `created_at`, `updated_at`: Audit timestamps

**Relationships:**
- Many-to-One with `colleges`
- Many-to-Many with `batches` (via `batch_companies`)
- One-to-Many with `placements`

---

### 19. Batch Companies

**Purpose**: Many-to-Many relationship between batches and companies.

```sql
CREATE TABLE batch_companies (
    batch_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    hiring_count INTEGER,  -- Number of positions available
    requirements TEXT,      -- Job requirements
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (batch_id, company_id),
    CONSTRAINT fk_batch_companies_batch FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE,
    CONSTRAINT fk_batch_companies_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE
);
```

**Fields:**
- `batch_id`: Foreign key to batches
- `company_id`: Foreign key to companies
- `hiring_count`: Number of positions available
- `requirements`: Job requirements/description
- `created_at`: When company was linked to batch

**Relationships:**
- Many-to-Many: `batches` ↔ `companies`

---

### 20. Placements

**Purpose**: Track student job applications and placements.

```sql
CREATE TABLE placements (
    id BIGSERIAL PRIMARY KEY,
    student_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'APPLIED' CHECK (status IN ('APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED')),
    applied_date DATE NOT NULL,
    failure_reason TEXT,  -- If rejected, why?
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_placements_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    CONSTRAINT fk_placements_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE
);
```

**Fields:**
- `id`: Primary key
- `student_id`: Foreign key to students
- `company_id`: Foreign key to companies
- `status`: APPLIED, INTERVIEW, OFFER, REJECTED
- `applied_date`: When student applied
- `failure_reason`: Reason for rejection (if applicable)
- `notes`: Additional notes
- `created_at`, `updated_at`: Audit timestamps

**Relationships:**
- Many-to-One with `students`
- Many-to-One with `companies`

**Status Flow:**
```
APPLIED → INTERVIEW → OFFER / REJECTED
```

---

## Entity Relationship Diagram

### High-Level ERD

```
┌───────────────────┐
│     COLLEGES      │ (Tenant Root)
├───────────────────┤
│ id (PK)           │
│ name              │
│ code              │
│ status            │
└─────────┬─────────┘
          │
          │ 1
          │
          │ N
┌─────────▼─────────┐
│       USERS       │ (Authentication)
├───────────────────┤
│ id (PK)           │
│ college_id (FK)    │──┐
│ email             │  │
│ password_hash     │  │
│ is_active         │  │
└─────────┬─────────┘  │
          │            │
          │ M          │
          │            │
          │ N          │
┌─────────▼─────────┐  │
│    USER_ROLES     │  │
├───────────────────┤  │
│ user_id (FK)      │  │
│ role_id (FK)      │  │
└─────────┬─────────┘  │
          │            │
          │ N          │
          │            │
          │ 1          │
┌─────────▼─────────┐  │
│       ROLES       │  │
├───────────────────┤  │
│ id (PK)           │  │
│ name              │  │
└───────────────────┘  │
                       │
          ┌────────────┘
          │
          │ 1
          │
          │ 1
┌─────────▼─────────┐     ┌───────────────────┐
│   COLLEGE_ADMINS  │     │     TRAINERS      │
├───────────────────┤     ├───────────────────┤
│ id (PK)           │     │ id (PK)           │
│ user_id (FK)      │     │ user_id (FK)      │
│ college_id (FK)   │     │ college_id (FK)   │
│ full_name         │     │ specialization    │
└───────────────────┘     └─────────┬─────────┘
                                     │
                                     │ M
                                     │
                                     │ N
┌───────────────────┐     ┌─────────▼─────────┐
│      STUDENTS     │     │   BATCH_TRAINERS │
├───────────────────┤     ├───────────────────┤
│ id (PK)           │     │ batch_id (FK)     │
│ user_id (FK)      │     │ trainer_id (FK)   │
│ college_id (FK)   │     └─────────┬─────────┘
│ roll_number       │               │
└─────────┬─────────┘               │
          │                          │
          │ M                        │
          │                          │
          │ N                        │
┌─────────▼─────────┐     ┌─────────▼─────────┐
│ BATCH_ENROLLMENTS │     │      BATCHES      │
├───────────────────┤     ├───────────────────┤
│ id (PK)           │     │ id (PK)           │
│ batch_id (FK)     │─────┤ college_id (FK)   │
│ student_id (FK)   │     │ name              │
│ status            │     │ status            │
└───────────────────┘     └─────────┬─────────┘
                                    │
                                    │ 1
                                    │
                                    │ 1
                          ┌─────────▼─────────┐
                          │      SYLLABI      │
                          ├───────────────────┤
                          │ id (PK)           │
                          │ batch_id (FK)      │
                          └─────────┬─────────┘
                                    │
                                    │ 1
                                    │
                                    │ N
                          ┌─────────▼─────────┐
                          │  SYLLABUS_TOPICS  │
                          ├───────────────────┤
                          │ id (PK)           │
                          │ syllabus_id (FK)  │
                          │ title             │
                          └─────────┬─────────┘
                                    │
                                    │ 1
                                    │
                                    │ N
                          ┌─────────▼─────────┐
                          │  TOPIC_PROGRESS   │
                          ├───────────────────┤
                          │ id (PK)           │
                          │ student_id (FK)   │
                          │ syllabus_topic_id │
                          │ status            │
                          └───────────────────┘
```

### Skills & Companies Relationships

```
┌───────────────────┐          ┌───────────────────┐
│      SKILLS       │          │     COMPANIES     │
├───────────────────┤          ├───────────────────┤
│ id (PK)           │          │ id (PK)           │
│ name              │          │ college_id (FK)   │
│ category          │          │ name              │
└─────────┬─────────┘          └─────────┬─────────┘
          │                              │
          │ 1                            │ 1
          │                              │
          │ N                            │ N
┌─────────▼─────────┐          ┌─────────▼─────────┐
│  STUDENT_SKILLS   │          │   BATCH_COMPANIES │
├───────────────────┤          ├───────────────────┤
│ student_id (FK)   │          │ batch_id (FK)     │
│ skill_id (FK)     │          │ company_id (FK)   │
│ proficiency_level │          └─────────┬─────────┘
└───────────────────┘                    │
                                         │
                                         │ 1
                                         │
                                         │ N
                               ┌─────────▼─────────┐
                               │     PLACEMENTS    │
                               ├───────────────────┤
                               │ id (PK)           │
                               │ student_id (FK)   │
                               │ company_id (FK)   │
                               │ status            │
                               └───────────────────┘
```

---

## Class Diagram

### Java Entity Classes Structure

```
┌─────────────────────────────────────────────────────────┐
│                    BaseEntity                            │
│  (Abstract - in common.entity package)                  │
├─────────────────────────────────────────────────────────┤
│  + Long id                                              │
│  + LocalDateTime createdAt                              │
│  + LocalDateTime updatedAt                              │
└────────────────────┬────────────────────────────────────┘
                     │
                     │ extends
                     │
        ┌────────────┼────────────┐
        │            │            │
        ▼            ▼            ▼
┌─────────────┐ ┌──────────┐ ┌──────────┐
│   College   │ │   User   │ │  Batch   │
├─────────────┤ ├──────────┤ ├──────────┤
│ + String    │ │ + String │ │ + String │
│   name      │ │   email  │ │   name   │
│ + String    │ │ + String │ │ + String │
│   code      │ │   password│ │   status│
│ + String    │ │   Hash   │ │ + Date   │
│   status    │ │ + Long   │ │   start  │
│             │ │   college│ │ + Date   │
│             │ │   Id    │ │   end    │
└─────────────┘ └──────────┘ └──────────┘
```

### Authentication & Authorization Classes

```
┌─────────────┐         ┌─────────────┐
│    User    │         │    Role     │
├─────────────┤         ├─────────────┤
│ + Long id  │         │ + Long id  │
│ + Long     │         │ + String   │
│   collegeId│         │   name     │
│ + String   │         └─────────────┘
│   email    │
│ + String   │         ┌─────────────┐
│   password │         │ UserRole    │
│   Hash     │         │ (Junction)  │
│ + Boolean  │         ├─────────────┤
│   isActive │         │ + Long     │
└─────────────┘         │   userId   │
                        │ + Long     │
┌─────────────┐         │   roleId   │
│RefreshToken│         └─────────────┘
├─────────────┤
│ + Long id  │
│ + Long     │
│   userId   │
│ + String   │
│   tokenHash│
│ + Local    │
│   DateTime │
│   expiresAt│
│ + Boolean  │
│   revoked  │
└─────────────┘
```

### Profile Classes

```
┌─────────────┐         ┌─────────────┐         ┌─────────────┐
│   Student  │         │  Trainer   │         │CollegeAdmin │
├─────────────┤         ├─────────────┤         ├─────────────┤
│ + Long id  │         │ + Long id  │         │ + Long id  │
│ + Long     │         │ + Long     │         │ + Long     │
│   userId   │         │   userId   │         │   userId   │
│ + Long     │         │ + Long     │         │ + Long     │
│   collegeId│         │   collegeId│         │   collegeId│
│ + String   │         │ + String   │         │ + String   │
│   rollNum  │         │   fullName │         │   fullName │
│ + String   │         │ + String   │         │ + String   │
│   degree   │         │   dept     │         │   phone    │
│ + String   │         │ + String   │         └─────────────┘
│   branch   │         │   spec     │
│ + Integer  │         │ + String   │
│   year     │         │   bio      │
└─────────────┘         └─────────────┘
```

### Training & Progress Classes

```
┌─────────────┐         ┌─────────────┐         ┌─────────────┐
│   Batch    │         │  Syllabus  │         │SyllabusTopic│
├─────────────┤         ├─────────────┤         ├─────────────┤
│ + Long id  │         │ + Long id  │         │ + Long id  │
│ + Long     │         │ + Long     │         │ + Long     │
│   collegeId│         │   batchId  │         │   syllabusId│
│ + String   │         │ + String   │         │ + String   │
│   name     │         │   title    │         │   title    │
│ + String   │         │ + String   │         │ + String   │
│   status   │         │   desc     │         │   difficulty│
│ + Date     │         └─────────────┘         │ + Integer  │
│   start    │                                  │   orderIdx │
│ + Date     │                                  └─────────────┘
│   end      │
└─────────────┘         ┌─────────────┐
                        │TopicProgress│
┌─────────────┐         ├─────────────┤
│BatchEnroll │         │ + Long id  │
├─────────────┤         │ + Long     │
│ + Long id  │         │   studentId│
│ + Long     │         │ + Long     │
│   batchId  │         │   topicId  │
│ + Long     │         │ + String   │
│   studentId│         │   status   │
│ + String   │         │ + Long     │
│   status   │         │   updatedBy│
└─────────────┘         │ + String   │
                        │   comment  │
                        └─────────────┘
```

---

## Indexes & Performance

### Primary Indexes (Automatic)

All tables have primary key indexes (automatic):
- `colleges_pkey` on `colleges(id)`
- `users_pkey` on `users(id)`
- `students_pkey` on `students(id)`
- etc.

### Foreign Key Indexes

```sql
-- Users
CREATE INDEX idx_users_college_id ON users(college_id);
CREATE INDEX idx_users_email ON users(email);  -- Already unique, but explicit

-- Students
CREATE INDEX idx_students_college_id ON students(college_id);
CREATE INDEX idx_students_user_id ON students(user_id);
CREATE INDEX idx_students_roll_number ON students(roll_number);  -- For lookups

-- Trainers
CREATE INDEX idx_trainers_college_id ON trainers(college_id);
CREATE INDEX idx_trainers_user_id ON trainers(user_id);

-- College Admins
CREATE INDEX idx_college_admins_college_id ON college_admins(college_id);
CREATE INDEX idx_college_admins_user_id ON college_admins(user_id);

-- Batches
CREATE INDEX idx_batches_college_id ON batches(college_id);
CREATE INDEX idx_batches_status ON batches(status);  -- Filter by status

-- Batch Enrollments
CREATE INDEX idx_batch_enrollments_batch_id ON batch_enrollments(batch_id);
CREATE INDEX idx_batch_enrollments_student_id ON batch_enrollments(student_id);
CREATE INDEX idx_batch_enrollments_status ON batch_enrollments(status);

-- Batch Trainers
CREATE INDEX idx_batch_trainers_batch_id ON batch_trainers(batch_id);
CREATE INDEX idx_batch_trainers_trainer_id ON batch_trainers(trainer_id);

-- Syllabus Topics
CREATE INDEX idx_syllabus_topics_syllabus_id ON syllabus_topics(syllabus_id);
CREATE INDEX idx_syllabus_topics_order_index ON syllabus_topics(syllabus_id, order_index);  -- Composite for ordering

-- Topic Progress
CREATE INDEX idx_topic_progress_student_id ON topic_progress(student_id);
CREATE INDEX idx_topic_progress_topic_id ON topic_progress(syllabus_topic_id);
CREATE INDEX idx_topic_progress_status ON topic_progress(status);
CREATE INDEX idx_topic_progress_student_topic ON topic_progress(student_id, syllabus_topic_id);  -- Composite for unique constraint

-- Student Skills
CREATE INDEX idx_student_skills_student_id ON student_skills(student_id);
CREATE INDEX idx_student_skills_skill_id ON student_skills(skill_id);

-- Feedback
CREATE INDEX idx_feedback_batch_id ON feedback(batch_id);
CREATE INDEX idx_feedback_from_user_id ON feedback(from_user_id);
CREATE INDEX idx_feedback_to_user_id ON feedback(to_user_id);

-- Companies
CREATE INDEX idx_companies_college_id ON companies(college_id);

-- Batch Companies
CREATE INDEX idx_batch_companies_batch_id ON batch_companies(batch_id);
CREATE INDEX idx_batch_companies_company_id ON batch_companies(company_id);

-- Placements
CREATE INDEX idx_placements_student_id ON placements(student_id);
CREATE INDEX idx_placements_company_id ON placements(company_id);
CREATE INDEX idx_placements_status ON placements(status);

-- Refresh Tokens
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);  -- For lookups
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);  -- For cleanup
```

### Composite Indexes

```sql
-- For multi-tenant queries (college_id + other filters)
CREATE INDEX idx_batches_college_status ON batches(college_id, status);
CREATE INDEX idx_students_college_roll ON students(college_id, roll_number);

-- For recommendation queries
CREATE INDEX idx_batch_enrollments_student_status ON batch_enrollments(student_id, status);
CREATE INDEX idx_topic_progress_student_status ON topic_progress(student_id, status);
```

---

## Data Types & Constraints

### Common Data Types

| Field Type | PostgreSQL Type | Java Type | Notes |
|------------|----------------|-----------|-------|
| ID | `BIGSERIAL` | `Long` | Auto-incrementing primary key |
| Name/Title | `VARCHAR(255)` | `String` | Variable length string |
| Email | `VARCHAR(255)` | `String` | With UNIQUE constraint |
| Text/Description | `TEXT` | `String` | Unlimited length |
| Boolean | `BOOLEAN` | `Boolean` | true/false |
| Date | `DATE` | `LocalDate` | Date only (no time) |
| Timestamp | `TIMESTAMP` | `LocalDateTime` | Date and time |
| Integer | `INTEGER` | `Integer` | 32-bit integer |
| Status | `VARCHAR(20)` | `String` | With CHECK constraint |

### Status Enums

All status fields use CHECK constraints:

```sql
-- Batch Status
CHECK (status IN ('UPCOMING', 'OPEN', 'ACTIVE', 'COMPLETED', 'CANCELLED'))

-- Enrollment Status
CHECK (status IN ('APPLIED', 'APPROVED', 'REJECTED', 'COMPLETED'))

-- Progress Status
CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'NEEDS_IMPROVEMENT'))

-- Placement Status
CHECK (status IN ('APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED'))
```

### Constraints Summary

**NOT NULL Constraints:**
- All primary keys
- All foreign keys (except `users.college_id` for SYSTEM_ADMIN)
- All status fields
- All email fields
- All name/title fields

**UNIQUE Constraints:**
- `users.email`
- `colleges.code`
- `students.roll_number`
- `users.id` in profile tables (one-to-one)
- Composite: `(batch_id, student_id)` in `batch_enrollments`
- Composite: `(student_id, syllabus_topic_id)` in `topic_progress`

**CHECK Constraints:**
- All status fields (enum values)
- `proficiency_level` BETWEEN 1 AND 5
- `rating` BETWEEN 1 AND 5

**Foreign Key Constraints:**
- All foreign keys have `ON DELETE CASCADE` (except `topic_progress.updated_by` which is `ON DELETE SET NULL`)

---

## Summary

### Total Tables: 20

1. **Core**: colleges
2. **Auth**: users, roles, user_roles, refresh_tokens
3. **Profiles**: college_admins, trainers, students
4. **Skills**: skills, student_skills
5. **Training**: batches, batch_trainers, batch_enrollments, syllabi, syllabus_topics
6. **Progress**: topic_progress
7. **Feedback**: feedback
8. **Companies**: companies, batch_companies, placements

### Key Design Decisions

✅ **Multi-tenancy**: `college_id` in all tenant-scoped tables  
✅ **Single User Table**: One authentication table with roles  
✅ **Separation**: Auth (users) ≠ Profiles (students/trainers)  
✅ **Normalized**: 3NF normalized structure  
✅ **Indexed**: All foreign keys and frequently queried columns  
✅ **Constraints**: CHECK constraints for data integrity  
✅ **Audit Trail**: `created_at` and `updated_at` on all tables  

---

**Next Step**: Create Flyway migration file `V1__init_core_tables.sql` with all these tables!

