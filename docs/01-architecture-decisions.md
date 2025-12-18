# Architecture Decisions (ADR - Architecture Decision Records)

This document captures all major architectural decisions for SkillBridge, explaining **what** we chose and **why** we chose it.

---

## 1. Database & Supabase Strategy

### 1.1 PostgreSQL: Supabase vs Self-Hosted

**Decision:** ✅ Use Supabase PostgreSQL (managed)

**Why:**
- **Managed Service Benefits**: Supabase provides automatic backups, monitoring, and upgrades
- **No Infrastructure Overhead**: Focus on building features, not managing databases
- **Full Control**: You still own the database schema and can write raw SQL
- **Migration Path**: Easy to migrate to self-hosted PostgreSQL later if needed
- **Cost-Effective**: Free tier available for development, reasonable pricing for production

**Important Note:** 
We're using Supabase **only as a managed PostgreSQL provider**. We're NOT using Supabase's Auth, Storage APIs, or client libraries. Think of it as "AWS RDS but simpler."

---

### 1.2 Authentication: Supabase Auth vs Spring Boot JWT

**Decision:** ❌ Do NOT use Supabase Auth  
**Decision:** ✅ Use custom JWT authentication in Spring Boot

**Why:**
- **Custom Roles**: We need 4 specific roles (System Admin, College Admin, Trainer, Student) with complex permissions
- **Tenant-Aware Security**: Our security must be aware of which college (tenant) a user belongs to
- **Deep Authorization**: We need method-level security, resource-level permissions, and cross-tenant access rules
- **Spring Security Integration**: Spring Boot has excellent RBAC support that integrates seamlessly with our backend
- **Complexity Avoidance**: Mixing Supabase Auth with Spring Security would create two authentication systems to maintain

**What This Means:**
- We'll implement JWT token generation and validation in Spring Boot
- Tokens will contain: user ID, role, college ID (tenant), permissions
- Spring Security will handle authorization based on these tokens

---

### 1.3 Data Access: Supabase Client vs JPA

**Decision:** ❌ Do NOT use Supabase client libraries  
**Decision:** ✅ Use Spring Data JPA directly with PostgreSQL

**Why:**
- **Full ORM Support**: JPA/Hibernate provides object-relational mapping, lazy loading, and relationship management
- **Domain-Driven Design**: We can model our domain entities naturally in Java
- **Transaction Management**: Spring handles complex transactions across multiple database operations
- **Query Flexibility**: JPA Criteria API, native queries, and Spring Data repositories give us full SQL power
- **Multi-Tenancy**: Hibernate filters make tenant isolation straightforward
- **Type Safety**: Compile-time checking vs runtime errors with client libraries

**What This Means:**
- We connect to Supabase PostgreSQL using a standard JDBC connection string
- All data access goes through Spring Data JPA repositories
- We write Java entities, not SQL queries (mostly)

---

## 2. Multi-Tenancy Strategy

### 2.1 Tenant Column Strategy

**Decision:** ✅ Use `college_id` as the tenant identifier

**Why:**
- **Domain Clarity**: In our domain, each tenant IS a college. Using `college_id` makes the code self-documenting
- **Explicit Relationships**: When you see `college_id` in a table, you immediately understand the tenant boundary
- **No Abstraction Overhead**: We don't need a generic "tenant" concept when we have a concrete "college" concept

**Rule:**
- Every tenant-scoped table includes `college_id`
- Platform-level tables (e.g., `system_admins`, `colleges`) do NOT include `college_id`

**Example:**
```sql
-- Tenant-scoped (has college_id)
students (id, college_id, name, email, ...)
batches  (id, college_id, name, status, ...)
trainers (id, college_id, name, ...)

-- Platform-level (no college_id)
colleges (id, name, domain, ...)
system_admins (id, email, ...)
```

---

### 2.2 Row-Level Security (RLS)

**Decision:** ❌ Do NOT use PostgreSQL RLS initially  
**Decision:** ✅ Enforce tenancy at application level

**Why:**
- **Debugging**: Application-level filters are easier to debug and test
- **Flexibility**: We can easily change tenant isolation rules without database migrations
- **Onboarding**: New developers can understand the code without learning PostgreSQL RLS syntax
- **Spring Integration**: Spring Security and Hibernate filters work naturally with application-level tenancy
- **Performance**: Application-level filtering is sufficient for our scale

**Implementation Approach:**
1. Extract `college_id` from JWT token after authentication
2. Store in Spring Security context (ThreadLocal)
3. Automatically inject `college_id` into every repository query
4. Use Hibernate `@Filter` annotation for automatic filtering

**Example:**
```java
// Automatically filters by college_id from security context
@Filter(name = "collegeFilter", condition = "college_id = :collegeId")
public class Student {
    @Column(name = "college_id")
    private Long collegeId;
}
```

---

### 2.3 System Admin Queries

**Decision:** ✅ Allow cross-tenant queries for SYSTEM_ADMIN role

**Why:**
- **Platform Metrics**: System admins need to see aggregated statistics across all colleges
- **Analytics**: Cross-tenant analytics are essential for platform health monitoring
- **Security**: Only SYSTEM_ADMIN role can bypass tenant filters (enforced by Spring Security)

**Implementation:**
- SYSTEM_ADMIN role bypasses tenant filter in repositories
- All other roles (COLLEGE_ADMIN, TRAINER, STUDENT) are strictly tenant-scoped
- Method-level security annotations ensure proper access control

---

## 3. Recommendation Engine

### 3.1 Recommendation Strategy

**Decision:** ✅ Start with rule-based scoring (no ML initially)

**Why:**
- **Simplicity**: Rule-based systems are easier to understand, debug, and modify
- **Transparency**: Students can see WHY a batch was recommended (explainable)
- **Fast Iteration**: We can quickly adjust scoring rules based on feedback
- **No Data Dependency**: ML requires historical data; we don't have it yet
- **Future Path**: Easy to replace with ML later without changing the API

**Initial Signals:**
1. **Skill Match Score**: How many of the batch's required skills does the student have?
2. **Learning Opportunity Score**: How many new skills would the student learn?
3. **Company Alignment Score**: Are companies hiring for skills in this batch?

---

### 3.2 Scoring Weights

**Decision:** ✅ Configurable via application config (not database)

**Why:**
- **No Code Deployment**: Change weights without redeploying code
- **No Admin UI Needed**: Simple config file changes
- **Version Control**: Config changes are tracked in Git
- **Environment-Specific**: Different weights for dev/staging/production

**Example Configuration:**
```yaml
# application.yml
recommendation:
  weights:
    skill-match: 0.4
    learning-opportunity: 0.3
    company-alignment: 0.3
```

---

### 3.3 Caching Strategy

**Decision:** ✅ Compute on-demand initially  
**Decision:** ➡️ Cache results in Redis later

**Why:**
- **Data Freshness**: Student profiles, batch details, and company info change frequently
- **Simplicity**: On-demand computation is simpler to implement and debug
- **No Premature Optimization**: We don't know the performance characteristics yet
- **Future Optimization**: Easy to add Redis caching when we identify bottlenecks

**When to Add Caching:**
- When recommendation computation takes > 500ms
- When we see high load on recommendation endpoints
- When we have enough data to identify stable vs volatile factors

---

## 4. Project Structure

### 4.1 Repository Strategy

**Decision:** ✅ Separate repositories

**Structure:**
```
skillbridge-backend/   (Spring Boot)
skillbridge-frontend/  (React + TypeScript)
```

**Why:**
- **Independent CI/CD**: Deploy frontend and backend separately
- **Team Ownership**: Different teams can own different repos
- **Technology Flexibility**: Can swap frontend framework later without affecting backend
- **Scaling**: Can scale frontend and backend independently
- **Cleaner Dependencies**: Frontend doesn't need Java build tools

---

### 4.2 Package Naming

**Decision:** ✅ `com.skillbridge`

**Structure:**
```
com.skillbridge
 ├── auth
 ├── tenant
 ├── college
 ├── student
 ├── trainer
 ├── batch
 ├── syllabus
 ├── progress
 ├── feedback
 ├── company
 ├── placement
 ├── recommendation
 ├── reporting
 └── common
```

**Why:**
- **Industry Standard**: Reverse domain notation is Java convention
- **Namespace Clarity**: Prevents conflicts with other libraries
- **Professional**: Standard practice in enterprise Java applications

---

### 4.3 API Versioning

**Decision:** ✅ Use `/api/v1` from day one

**Why:**
- **Zero Cost Now**: Adding versioning is trivial when starting
- **Massive Cost Later**: Changing APIs without versioning breaks all clients
- **Future-Proofing**: Allows breaking changes in v2 without affecting v1
- **Best Practice**: Industry standard for REST APIs

**Example:**
```
/api/v1/students
/api/v1/batches
/api/v2/students  (future, with breaking changes)
```

---

## 5. Authentication & Security

### 5.1 Password Rules

**Decision:** ✅ Enforce basic enterprise rules

**Rules:**
- Minimum 8 characters
- At least 1 uppercase letter
- At least 1 lowercase letter
- At least 1 number
- At least 1 special character

**Reset Flow:**
- Email token sent to user
- Token expires in 15-30 minutes
- One-time use token

**Why:**
- **Security**: Prevents weak passwords
- **Compliance**: Meets enterprise security standards
- **User Experience**: Clear rules prevent frustration

---

### 5.2 Refresh Token Storage

**Decision:** ✅ Store refresh tokens in database

**Why:**
- **Security**: Can revoke tokens immediately (e.g., if account compromised)
- **Auditability**: Track token usage and revocations
- **Simplicity**: No need for Redis initially
- **Persistence**: Tokens survive server restarts

**Schema:**
```sql
refresh_tokens (
    id,
    user_id,
    token_hash,
    expires_at,
    revoked,
    created_at
)
```

**Future Optimization:** Can move to Redis for high-scale scenarios

---

### 5.3 Session Management

**Decision:** ✅ Stateless JWT-based (no server sessions)

**Why:**
- **Scalability**: No need for sticky sessions or session replication
- **Microservices Ready**: Tokens work across multiple services
- **Stateless**: Each request is independent
- **Mobile Friendly**: Works seamlessly with mobile apps

**Flow:**
1. User logs in → receives access token (short-lived, 15 min) + refresh token (long-lived, 7 days)
2. Each request includes access token in Authorization header
3. When access token expires, use refresh token to get new access token
4. Refresh token rotation for security

---

## 6. Data Models & Relationships

### 6.1 Skills

**Decision:** ✅ Predefined skill catalog + free-form extension

**Why:**
- **Analytics**: Predefined skills enable aggregation and reporting
- **Consistency**: Prevents "Java" vs "java" vs "JAVA" chaos
- **Flexibility**: Free-form skills allow for emerging technologies
- **Recommendations**: Easier to match predefined skills in recommendation engine

**Implementation:**
- Core skill catalog (Java, Python, React, etc.) in database
- Students can add custom skills if not in catalog
- Admin can promote popular custom skills to catalog

---

### 6.2 Proficiency Levels

**Decision:** ✅ Numeric scale (1-5)

**Mapping:**
- 1: Beginner
- 2: Basic
- 3: Intermediate
- 4: Advanced
- 5: Expert

**Why:**
- **Algorithm-Friendly**: Numbers work better for scoring and calculations
- **Comparable**: Easy to compare proficiency across students
- **Flexible**: Can adjust scale later without data migration
- **Standard**: Common industry practice

---

### 6.3 Batch Status Transitions

**Decision:** ✅ Strict state machine

**States:**
```
UPCOMING → OPEN → ACTIVE → COMPLETED / CANCELLED
```

**Why:**
- **Data Integrity**: Prevents invalid states (e.g., "Active" batch that's "Completed")
- **Analytics**: Clean state transitions enable accurate reporting
- **Business Logic**: Each state has specific allowed actions
- **Auditing**: Clear history of batch lifecycle

**Implementation:**
- State enum with transition rules
- Validation in service layer
- Database constraints (if possible)

---

## 7. File Storage

### 7.1 Storage Provider

**Decision:** ✅ Supabase Storage initially

**Why:**
- **Integrated**: Already using Supabase for database
- **S3-Compatible**: Can migrate to AWS S3 later without code changes
- **No Extra Infrastructure**: One less service to manage
- **Cost-Effective**: Free tier for development

**Future Migration:**
- Supabase Storage API is S3-compatible
- Can switch to AWS S3 by changing connection string
- No code changes needed

---

### 7.2 File Rules

**Decision:**
- **Resumes**: PDF only, max 5 MB
- **Profile Images**: JPG/PNG, max 2 MB
- **Virus Scanning**: Add later (not MVP)

**Why:**
- **Security**: PDFs are safer than Word documents
- **User Experience**: Reasonable size limits
- **Performance**: Smaller files = faster uploads/downloads
- **MVP Focus**: Virus scanning can be added post-MVP

---

## 8. Development Workflow

### 8.1 Code Style

**Decision:**
- **Java**: Standard Spring conventions (Google Java Style Guide)
- **TypeScript**: ESLint + Prettier (standard config)

**Why:**
- **Consistency**: Team can focus on logic, not style debates
- **Tooling**: Auto-formatting prevents style discussions
- **Industry Standard**: Follows widely-accepted conventions

---

### 8.2 Testing Strategy

**Decision:** ✅ Start with unit tests immediately

**Focus:**
- Service layer unit tests (business logic)
- Repository layer tests (data access)
- Integration tests added after MVP

**Why:**
- **Quality**: Catch bugs early
- **Refactoring Confidence**: Tests enable safe code changes
- **Documentation**: Tests serve as executable documentation
- **MVP First**: Integration tests can wait until core features are stable

---

### 8.3 API Documentation

**Decision:** ✅ Swagger / OpenAPI from day one

**Why:**
- **Contract**: Clear contract between frontend and backend
- **Onboarding**: New developers can understand APIs immediately
- **Testing**: Can test APIs directly from Swagger UI
- **Zero Cost**: Spring Boot has excellent Swagger integration
- **Future**: Can generate client libraries from OpenAPI spec

---

## 9. Final Architectural Position

**One-Sentence Summary:**

> SkillBridge will use Supabase-managed PostgreSQL with a Spring Boot backend, implementing application-level multi-tenancy, JWT-based security, rule-based recommendations, and a modular monolithic design optimized for scalability and future service extraction.

---

## Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2024 | Use Supabase PostgreSQL | Managed service, no infra overhead |
| 2024 | Custom JWT in Spring Boot | Complex RBAC requirements |
| 2024 | Spring Data JPA | Full ORM, transaction management |
| 2024 | Application-level multi-tenancy | Simpler than RLS, easier debugging |
| 2024 | Rule-based recommendations | No ML data yet, transparent scoring |
| 2024 | Separate repos | Independent deployment, scaling |

---

## Future Considerations

These decisions may change as the project evolves:

1. **Multi-tenancy**: May move to schema-per-tenant for premium clients
2. **Recommendations**: May add ML-based recommendations after collecting data
3. **Caching**: Will add Redis when performance requires it
4. **File Storage**: May migrate to AWS S3 for enterprise clients
5. **Microservices**: Modular monolith designed to extract services later

