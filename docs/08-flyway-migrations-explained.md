# Flyway Migrations - Complete Guide

This document explains **everything** about Flyway: what it is, how it works, why we use it, and how to use it effectively.

---

## Table of Contents

1. [What is Flyway?](#what-is-flyway)
2. [Why Use Flyway?](#why-use-flyway)
3. [How Flyway Works](#how-flyway-works)
4. [Migration File Naming](#migration-file-naming)
5. [Migration Execution Flow](#migration-execution-flow)
6. [Flyway Schema History](#flyway-schema-history)
7. [Best Practices](#best-practices)
8. [Common Patterns](#common-patterns)
9. [Troubleshooting](#troubleshooting)
10. [Advantages & Benefits](#advantages--benefits)

---

## What is Flyway?

### Simple Definition

**Flyway** is a database migration tool that automatically runs SQL scripts to create and update your database schema.

### Analogy

Think of Flyway like a **recipe book** for your database:

- **Recipe** = Migration file (SQL script)
- **Cookbook** = `db/migration/` folder
- **Chef** = Flyway (runs recipes automatically)
- **Recipe History** = `flyway_schema_history` table (tracks what ran)

### What It Does

1. **Scans** your `db/migration/` folder for SQL files
2. **Checks** which migrations already ran (via `flyway_schema_history` table)
3. **Runs** only new migrations (in order)
4. **Records** what ran in the history table

---

## Why Use Flyway?

### Problem Without Migrations

**Scenario:** You need to create database tables

**Without Flyway:**
```
❌ Connect to database manually
❌ Run SQL commands one by one
❌ Hard to track what changed
❌ Different developers have different schemas
❌ Production database might be different
❌ No history of changes
```

**With Flyway:**
```
✅ SQL files in version control (Git)
✅ Automatic execution on app startup
✅ Same schema everywhere (dev, staging, production)
✅ Complete history of all changes
✅ Easy to reproduce database from scratch
```

### Real-World Example

**Day 1:** Create users table
```sql
-- V1__create_users_table.sql
CREATE TABLE users (...);
```

**Day 2:** Add phone column
```sql
-- V2__add_phone_to_users.sql
ALTER TABLE users ADD COLUMN phone VARCHAR(20);
```

**Day 3:** Create batches table
```sql
-- V3__create_batches_table.sql
CREATE TABLE batches (...);
```

**What Happens:**
1. Day 1: Flyway runs V1 → creates `users` table
2. Day 2: Flyway runs V2 → adds `phone` column
3. Day 3: Flyway runs V3 → creates `batches` table

**Next Time App Starts:**
- Flyway sees: V1, V2, V3 all already ran
- Skips them all
- Only runs new migrations (V4, V5, etc.)

---

## How Flyway Works

### Step-by-Step Process

```
1. App Starts
   ↓
2. Spring Boot Initializes Flyway
   ↓
3. Flyway Connects to Database
   ↓
4. Flyway Checks: Does flyway_schema_history table exist?
   ├─ NO → Creates it (first time)
   └─ YES → Continues
   ↓
5. Flyway Scans db/migration/ Folder
   ↓
6. Finds SQL Files: V1__..., V2__..., V3__...
   ↓
7. Flyway Checks Database: Which migrations already ran?
   ↓
8. Compares:
   - Files found: V1, V2, V3
   - Database history: V1, V2
   ↓
9. Runs Missing Migrations: V3
   ↓
10. Records in flyway_schema_history
    ↓
11. App Continues Starting
```

### Visual Flow

```
┌─────────────────┐
│  App Starts     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Flyway Scans    │
│ db/migration/   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐      ┌──────────────────┐
│ Found Files:    │      │ Check Database:  │
│ V1__create.sql  │      │ flyway_schema_   │
│ V2__add.sql     │      │ history table    │
│ V3__update.sql  │      └────────┬─────────┘
└────────┬────────┘               │
         │                        │
         └──────────┬──────────────┘
                    │
                    ▼
         ┌──────────────────┐
         │ Compare:          │
         │ Files: V1,V2,V3   │
         │ History: V1,V2    │
         └──────────┬─────────┘
                    │
                    ▼
         ┌──────────────────┐
         │ Run Missing: V3  │
         └──────────┬────────┘
                    │
                    ▼
         ┌──────────────────┐
         │ Record in        │
         │ flyway_schema_    │
         │ history           │
         └──────────┬────────┘
                    │
                    ▼
         ┌──────────────────┐
         │ App Continues     │
         └──────────────────┘
```

---

## Migration File Naming

### Format

```
V{version}__{description}.sql
```

### Components

1. **`V`** = Version prefix (required, uppercase)
2. **`{version}`** = Version number (1, 2, 3... or 1.1, 1.2...)
3. **`__`** = Double underscore separator (required)
4. **`{description}`** = Human-readable description (optional but recommended)
5. **`.sql`** = File extension

### Examples

```
✅ V1__create_users_table.sql
✅ V2__add_phone_to_users.sql
✅ V3__create_batches_table.sql
✅ V4__add_indexes.sql
✅ V1_1__fix_users_email.sql  (version 1.1)
```

### Invalid Names

```
❌ v1__create.sql          (lowercase V)
❌ V1_create.sql           (single underscore)
❌ 1__create.sql           (missing V)
❌ V1create.sql            (missing separator)
❌ V1__create.txt          (wrong extension)
```

### Why Double Underscore?

Flyway uses `__` to separate version from description:
- `V1__create_users` → Version: `1`, Description: `create_users`
- This allows Flyway to parse and display migrations clearly

---

## Migration Execution Flow

### First Time (Empty Database)

```
1. App starts
2. Flyway connects to database
3. Database is empty (no tables)
4. Flyway creates flyway_schema_history table
5. Flyway finds: V1__init_core_tables.sql
6. Flyway checks history: Empty (no migrations ran)
7. Flyway runs V1__init_core_tables.sql
   → Creates all 20 tables
8. Flyway records in history:
   - version: "1"
   - description: "init core tables"
   - success: true
9. App continues
```

### Second Time (V1 Already Ran)

```
1. App starts
2. Flyway connects to database
3. Flyway finds: V1__init_core_tables.sql
4. Flyway checks history: V1 already ran
5. Flyway skips V1 (already done)
6. App continues
```

### Adding New Migration

```
1. You create: V2__add_new_feature.sql
2. App starts
3. Flyway finds: V1, V2
4. Flyway checks history: V1 ran, V2 didn't
5. Flyway runs V2__add_new_feature.sql
6. Flyway records V2 in history
7. App continues
```

---

## Flyway Schema History

### What is It?

Flyway creates a table called `flyway_schema_history` to track all migrations.

### Table Structure

```sql
flyway_schema_history
├── installed_rank    (1, 2, 3...)        -- Order migrations ran
├── version           ("1", "2", "3"...)  -- Migration version
├── description       ("create users", ...) -- Migration description
├── type              ("SQL")              -- Migration type
├── script            ("V1__create.sql")   -- File name
├── checksum          (hash)               -- File content hash
├── installed_on      (timestamp)          -- When it ran
├── installed_by      (username)           -- Who ran it
├── execution_time    (milliseconds)       -- How long it took
└── success           (true/false)         -- Did it succeed?
```

### Example Data

```
installed_rank | version | description          | success | installed_on
---------------|---------|---------------------|---------|------------------
1              | 1       | init core tables    | true    | 2024-12-18 10:30
2              | 2       | add phone to users  | true    | 2024-12-19 14:20
3              | 3       | create batches      | true    | 2024-12-20 09:15
```

### Why It Matters

1. **Tracks History**: See when each migration ran
2. **Prevents Re-runs**: Flyway won't run same migration twice
3. **Validates Changes**: Detects if migration file was modified
4. **Audit Trail**: Complete record of database changes

---

## Best Practices

### 1. Never Modify Existing Migrations

**❌ BAD:**
```
V1__create_users.sql (already ran in production)
You modify it → Add a column
Result: Flyway detects checksum mismatch → ERROR!
```

**✅ GOOD:**
```
V1__create_users.sql (don't touch it)
Create V2__add_column_to_users.sql (new migration)
```

**Why:**
- Once a migration runs in production, it's "locked"
- Changing it would break other environments
- Always create new migrations for changes

### 2. Sequential Version Numbers

**❌ BAD:**
```
V1__create_users.sql
V3__create_orders.sql  ← Skipped V2!
```

**✅ GOOD:**
```
V1__create_users.sql
V2__create_orders.sql
V3__add_phone.sql
```

**Why:**
- Flyway runs migrations in order
- Skipping versions causes confusion
- Use sequential numbers (1, 2, 3...)

### 3. One Change Per Migration (Usually)

**❌ BAD:**
```
V1__create_everything.sql
  - Creates users table
  - Creates orders table
  - Creates products table
  - Creates 20 other tables
```

**✅ GOOD:**
```
V1__create_users.sql
V2__create_orders.sql
V3__create_products.sql
```

**Why:**
- Easier to understand what each migration does
- Easier to debug if one fails
- Can rollback specific changes

**Exception:** Initial migration (V1) can create multiple related tables

### 4. Descriptive Names

**❌ BAD:**
```
V1__migration.sql
V2__update.sql
V3__fix.sql
```

**✅ GOOD:**
```
V1__init_core_tables.sql
V2__add_phone_to_users.sql
V3__create_batches_table.sql
```

**Why:**
- Names should describe what the migration does
- Makes it easy to find specific migrations
- Self-documenting code

### 5. Test Migrations Locally First

**Process:**
1. Create migration file
2. Test locally (dev database)
3. Verify it works
4. Commit to Git
5. Deploy to staging
6. Deploy to production

**Why:**
- Catch errors before production
- Verify migration doesn't break existing data
- Test rollback if needed

---

## Common Patterns

### Pattern 1: Create Table

```sql
-- V1__create_users_table.sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX idx_users_email ON users(email);
```

### Pattern 2: Add Column

```sql
-- V2__add_phone_to_users.sql
ALTER TABLE users ADD COLUMN phone VARCHAR(20);
CREATE INDEX idx_users_phone ON users(phone);
```

### Pattern 3: Add Foreign Key

```sql
-- V3__add_college_to_users.sql
ALTER TABLE users ADD COLUMN college_id BIGINT;
ALTER TABLE users ADD CONSTRAINT fk_users_college 
    FOREIGN KEY (college_id) REFERENCES colleges(id);
CREATE INDEX idx_users_college_id ON users(college_id);
```

### Pattern 4: Create Junction Table

```sql
-- V4__create_user_roles.sql
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id)
);
```

### Pattern 5: Insert Initial Data

```sql
-- V5__insert_roles.sql
INSERT INTO roles (name) VALUES 
    ('SYSTEM_ADMIN'),
    ('COLLEGE_ADMIN'),
    ('TRAINER'),
    ('STUDENT');
```

### Pattern 6: Add Index

```sql
-- V6__add_indexes.sql
CREATE INDEX idx_users_college_id ON users(college_id);
CREATE INDEX idx_batches_status ON batches(status);
```

---

## Troubleshooting

### Error: "Migration checksum mismatch"

**Problem:**
```
Migration V1__create_users.sql has checksum mismatch
Expected: 1234567890
Actual: 0987654321
```

**Cause:**
- Migration file was modified after it ran
- Flyway detects the change and errors

**Solution:**
- **Don't modify existing migrations!**
- Create a new migration (V2) to make changes

### Error: "Migration version already exists"

**Problem:**
```
Migration version 1 already exists
```

**Cause:**
- Two migration files with same version number
- Example: `V1__create.sql` and `V1__update.sql`

**Solution:**
- Rename one file to use next version number
- Example: `V1__create.sql` and `V2__update.sql`

### Error: "Migration failed"

**Problem:**
```
Migration V2__add_column.sql failed
SQL Error: column already exists
```

**Cause:**
- SQL syntax error
- Column/table already exists
- Foreign key constraint violation

**Solution:**
1. Fix the SQL in migration file
2. Check if change already applied manually
3. Test migration on clean database first

### Migration Not Running

**Problem:**
- Created `V2__new_migration.sql` but it's not running

**Possible Causes:**
1. **Wrong folder**: File not in `db/migration/`
2. **Wrong naming**: Not following `V{version}__{description}.sql`
3. **Flyway disabled**: Check `application.yaml` → `flyway.enabled: true`
4. **Already ran**: Check `flyway_schema_history` table

**Solution:**
- Verify file location and naming
- Check Flyway configuration
- Check migration history

---

## Advantages & Benefits

### 1. Version Control

**Benefit:**
- Database schema changes are in Git
- See history: "When did we add the phone column?"
- Rollback: Create new migration to undo changes

**Example:**
```bash
git log db/migration/
# See all schema changes over time
```

### 2. Reproducibility

**Benefit:**
- New developer: Clone repo → Run app → Database is set up automatically
- Same schema everywhere: dev, staging, production

**Example:**
```
Developer A: Has V1, V2, V3
Developer B: Clones repo → Gets V1, V2, V3
Both have identical database schemas
```

### 3. Team Collaboration

**Benefit:**
- Everyone gets the same migrations
- No "works on my machine" database issues
- Clear process for schema changes

**Example:**
```
Developer A: Creates V4__add_feature.sql
Commits to Git
Developer B: Pulls code → Gets V4
App starts → Flyway runs V4 automatically
Both have same schema
```

### 4. Safety

**Benefit:**
- Flyway validates migrations haven't been modified
- Prevents accidental changes to already-run migrations
- Tracks success/failure of each migration

**Example:**
```
Migration V1 ran successfully
Someone modifies V1 file
Flyway detects checksum mismatch → Error
Prevents accidental schema corruption
```

### 5. Order Matters

**Benefit:**
- Migrations run in order (V1, then V2, then V3...)
- Ensures dependencies are correct
- Predictable execution

**Example:**
```
V1: Creates users table
V2: Creates orders table (references users)
V3: Adds index to users table

Order is guaranteed: V1 → V2 → V3
```

### 6. Production Ready

**Benefit:**
- Same migrations run in dev, staging, production
- No manual SQL execution needed
- Automated deployment

**Example:**
```
Deploy to production:
1. Deploy code (includes migration files)
2. App starts
3. Flyway runs new migrations automatically
4. Database updated
```

---

## Our Migration: V1__init_core_tables.sql

### What It Does

Creates all 20 tables for SkillBridge:
- **Core**: colleges
- **Auth**: users, roles, user_roles, refresh_tokens
- **Profiles**: college_admins, trainers, students
- **Skills**: skills, student_skills
- **Training**: batches, batch_trainers, batch_enrollments, syllabi, syllabus_topics
- **Progress**: topic_progress
- **Feedback**: feedback
- **Companies**: companies, batch_companies, placements

### Structure

1. **Table Creation**: All CREATE TABLE statements
2. **Indexes**: Performance indexes on foreign keys and frequently queried columns
3. **Constraints**: CHECK constraints, UNIQUE constraints, FOREIGN KEY constraints
4. **Comments**: Documentation for each table
5. **Pre-populated Data**: Roles and sample skills

### Why All in One Migration?

- **Initial Setup**: This is the foundation
- **Related Tables**: All tables are interconnected
- **Atomic**: Either all tables created or none (transaction)
- **Future Migrations**: Will be smaller, focused changes

---

## Summary

### What is Flyway?
- Database migration tool
- Automatically runs SQL scripts
- Tracks what ran in `flyway_schema_history` table

### How It Works
1. Scans `db/migration/` folder
2. Checks which migrations already ran
3. Runs only new migrations (in order)
4. Records in history table

### Why Use It?
- ✅ Version controlled schema
- ✅ Reproducible databases
- ✅ Team collaboration
- ✅ Safety (validates changes)
- ✅ Production ready

### Best Practices
- Never modify existing migrations
- Sequential version numbers
- Descriptive names
- Test locally first
- One change per migration (usually)

### Our Migration
- **File**: `V1__init_core_tables.sql`
- **Creates**: All 20 tables
- **Location**: `src/main/resources/db/migration/`
- **Runs**: Automatically on app startup

---

**Next Step**: Run the app and watch Flyway create all tables! 🚀

