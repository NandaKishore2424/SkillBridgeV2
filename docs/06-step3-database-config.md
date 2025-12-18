# Step 3: Database Configuration - Complete Guide

This guide walks you through connecting Spring Boot to Supabase PostgreSQL, explaining **every step** and **why** we do it.

---

## 🎯 Goal

**What we want to achieve:**
- Connect Spring Boot application to Supabase PostgreSQL database
- Configure Spring Data JPA to work with PostgreSQL
- Set up Flyway for database migrations
- Test that the connection works

**Why this matters:**
- Without database connection, our app can't store or retrieve data
- This is the **foundation** for all data operations

---

## 📋 Step-by-Step Instructions

### Step 3.1: Get Supabase Database Credentials

**What you need to do:**

1. **Go to Supabase Dashboard**: https://supabase.com/dashboard
2. **Create a new project** (if you don't have one):
   - Click "New Project"
   - Enter project name: `skillbridge` (or any name)
   - Set a database password (save this!)
   - Choose a region (closest to you)
   - Wait for project to be created (~2 minutes)

3. **Get connection details**:
   - Go to **Settings** → **Database**
   - Find **Connection string** section
   - You'll see something like:
     ```
     Host: db.xxxxx.supabase.co
     Port: 5432
     Database: postgres
     User: postgres
     Password: [your-password]
     ```

**Why we need this:**
- **Host**: Where the database server is located
- **Port**: 5432 is PostgreSQL's default port
- **Database**: Usually `postgres` (default database)
- **User**: Database username (usually `postgres`)
- **Password**: The password you set when creating the project

**Security Note:**
- ⚠️ **Never commit passwords to Git!**
- We'll use environment variables or separate config files

---

### Step 3.2: Create Environment-Specific Configuration

**What we're doing:**
- Creating separate config files for different environments
- `application-dev.yml` for development
- `application-prod.yml` for production (later)

**Why separate files:**
- **Development**: Local database, debug logging
- **Production**: Production database, optimized settings
- **Security**: Different credentials for each environment

**Action:**
We'll create `application-dev.yml` for now.

---

### Step 3.3: Configure Database Connection

**What we're configuring:**

1. **DataSource**: How Spring Boot connects to database
2. **JPA/Hibernate**: How Spring Data JPA works with PostgreSQL
3. **Flyway**: Database migration tool

**Let's do it step by step:**

---

## ✅ Configuration Files Created

I've created the following files for you:

1. **`application.yaml`** - Main config (sets active profile)
2. **`application-dev.yaml`** - Development config (with placeholders)
3. **`application-local.yaml.example`** - Template for local secrets
4. **`.gitignore`** - Prevents committing passwords

---

## 🔧 Step 3.4: Fill in Your Database Credentials

### Option A: Using Local Config File (Recommended - More Secure)

**Why this approach:**
- Keeps passwords out of Git
- Each developer has their own local config
- `application-local.yaml` is in `.gitignore`

**Steps:**

1. **Copy the example file:**
   ```bash
   cd skillbridge-backend/src/main/resources
   cp application-local.yaml.example application-local.yaml
   ```

2. **Edit `application-local.yaml`:**
   - Replace `YOUR_SUPABASE_HOST` with your actual Supabase host
   - Replace `YOUR_SUPABASE_PASSWORD` with your actual password
   
   Example:
   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://db.abcdefghijklmnop.supabase.co:5432/postgres
       username: postgres
       password: my-secure-password-123
   ```

3. **Update `application.yaml` to use local profile:**
   ```yaml
   spring:
     profiles:
       active: local  # This will load application-local.yaml
   ```

### Option B: Direct Edit (Quick but Less Secure)

**If you want to test quickly:**

1. Edit `application-dev.yaml`
2. Replace placeholders with your actual values
3. ⚠️ **Remember**: Don't commit this file with real passwords!

---

## 📖 Configuration Explained (Line by Line)

Let me explain **every part** of the configuration:

### Database Connection (`datasource`)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://HOST:5432/postgres
    username: postgres
    password: YOUR_PASSWORD
```

**What is JDBC?**
- **JDBC** = Java Database Connectivity
- **Standard API** for connecting Java to databases
- **URL format**: `jdbc:postgresql://host:port/database`

**Breaking down the URL:**
- `jdbc:postgresql://` - Protocol (tells Java this is PostgreSQL)
- `HOST` - Your Supabase database host
- `5432` - PostgreSQL default port
- `postgres` - Database name (Supabase's default)

**Why `postgres` as database name?**
- Supabase creates a default database called `postgres`
- We'll create our tables in this database
- Later, we could create separate databases per tenant (advanced)

---

### Connection Pool (`hikari`)

```yaml
hikari:
  maximum-pool-size: 10
  minimum-idle: 5
  connection-timeout: 30000
```

**What is a connection pool?**
- **Problem**: Creating database connections is expensive (slow)
- **Solution**: Reuse connections instead of creating new ones each time

**How it works:**
1. App starts → Creates 5 connections (minimum-idle)
2. Request comes in → Uses existing connection from pool
3. Request done → Returns connection to pool (doesn't close it)
4. If all 10 connections busy → New requests wait

**Why these numbers?**
- **maximum-pool-size: 10** - Enough for small/medium apps
- **minimum-idle: 5** - Keeps some connections ready
- **timeout: 30000ms** - Fails fast if can't connect

**Teaching Point:**
- Connection pooling = **performance optimization**
- Without it, every request would create a new connection (slow!)

---

### JPA Configuration (`jpa`)

```yaml
jpa:
  database-platform: org.hibernate.dialect.PostgreSQLDialect
  hibernate:
    ddl-auto: none
  show-sql: true
```

**What is JPA?**
- **JPA** = Java Persistence API
- **Standard** for Java database access
- **Hibernate** = Most popular JPA implementation

**`database-platform`:**
- Tells Hibernate: "This is PostgreSQL, use PostgreSQL-specific SQL"
- Different databases have different SQL syntax
- Hibernate adapts SQL based on this setting

**`ddl-auto: none`:**
- **DDL** = Data Definition Language (CREATE TABLE, ALTER TABLE, etc.)
- **Options**:
  - `none` - Don't auto-create tables (we use Flyway)
  - `create` - Drop and create tables on startup (⚠️ deletes data!)
  - `update` - Auto-update schema (⚠️ can break things!)
  - `validate` - Just check if schema matches entities

**Why `none`?**
- **Control**: We want explicit control over schema changes
- **Version Control**: Flyway migrations are in Git
- **Safety**: No accidental data loss

**`show-sql: true`:**
- Logs all SQL queries to console
- **Useful for**: Debugging, learning, seeing what Hibernate generates
- **Production**: Set to `false` (too verbose)

---

### Flyway Configuration

```yaml
flyway:
  enabled: true
  locations: classpath:db/migration
  baseline-on-migrate: true
  validate-on-migrate: true
```

**What is Flyway?**
- **Database migration tool**
- **Manages schema changes** in version-controlled SQL files
- **Applies migrations** automatically on startup

**How it works:**
1. App starts → Flyway checks `db/migration/` folder
2. Finds files like `V1__create_tables.sql`, `V2__add_indexes.sql`
3. Checks database: "Which migrations already ran?"
4. Runs only new migrations (in order)
5. Records which migrations ran in `flyway_schema_history` table

**Configuration explained:**
- `enabled: true` - Turn on Flyway
- `locations` - Where to find migration files
- `baseline-on-migrate: true` - If database exists but no Flyway history, create baseline
- `validate-on-migrate: true` - Check that migrations haven't been modified

**Why Flyway?**
- **Version Control**: Database schema in Git
- **Reproducibility**: Same schema everywhere
- **Team Collaboration**: Everyone gets same migrations
- **Rollback**: Can create compensating migrations

---

### Logging Configuration

```yaml
logging:
  level:
    com.skillbridge: DEBUG
    org.hibernate.SQL: DEBUG
```

**What this does:**
- Controls how much information is logged
- **Levels**: ERROR → WARN → INFO → DEBUG → TRACE (most verbose)

**Why DEBUG for our package?**
- See detailed logs from our code
- Easier debugging during development

**Why DEBUG for Hibernate SQL?**
- See actual SQL queries being executed
- Learn how JPA translates Java to SQL
- Debug query performance issues

**Production:**
- Set to `INFO` or `WARN` (less verbose, better performance)

---

## 🧪 Step 3.5: Test the Connection

**Goal**: Verify that Spring Boot can connect to Supabase

**Steps:**

1. **Make sure you've filled in database credentials** in `application-local.yaml` or `application-dev.yaml`

2. **Run the application:**
   ```bash
   cd skillbridge-backend
   ./mvnw spring-boot:run
   ```

3. **What to look for:**
   - ✅ **Success**: App starts, no errors about database connection
   - ✅ **Flyway**: You'll see logs like "Flyway migration successful"
   - ❌ **Error**: "Connection refused" or "Authentication failed" → Check credentials

**Expected Logs (Success):**
```
Started SkillbridgeBackendApplication in 3.456 seconds
```

**Expected Logs (Connection Error):**
```
Cannot create PoolableConnectionFactory
Connection refused
```
→ Check your Supabase host and password

---

## 🔒 Security Best Practices

### ✅ DO:
- Use `application-local.yaml` (in `.gitignore`) for passwords
- Use environment variables in production
- Rotate passwords regularly

### ❌ DON'T:
- Commit passwords to Git
- Share passwords in chat/email
- Use same password for dev and production

---

## 📝 Summary

**What we did:**
1. ✅ Created environment-specific config files
2. ✅ Configured database connection (Supabase PostgreSQL)
3. ✅ Set up connection pooling (HikariCP)
4. ✅ Configured JPA/Hibernate
5. ✅ Enabled Flyway for migrations
6. ✅ Set up logging for debugging

**What each part does:**
- **DataSource**: How to connect to database
- **Connection Pool**: Reuse connections (performance)
- **JPA**: Object-relational mapping (Java ↔ Database)
- **Flyway**: Version-controlled schema changes
- **Logging**: See what's happening

**Next Steps:**
- Test the connection (run the app)
- If successful → Proceed to Step 4: Create first Flyway migration
- If errors → Check credentials and network connection

---

## 🎓 Learning Points

1. **Configuration Files**: YAML is cleaner than properties for nested configs
2. **Profiles**: Different configs for different environments
3. **Connection Pooling**: Reuse connections = better performance
4. **DDL Auto**: `none` = explicit control via migrations
5. **Flyway**: Database schema as code (version controlled)

**Ready to test?** Fill in your Supabase credentials and run the app! 🚀

