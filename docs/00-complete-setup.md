## Complete Setup Plan – SkillBridge (Modular Monolith)

This document gives a **high-level roadmap** of how we will set up the full SkillBridge project so it is:
- Modular monolithic
- Easy to understand (learning-first)
- Ready for future deployment and scaling

Each major step has:
- **What** we will do
- **Why** we do it

The detailed, step-by-step instructions for the backend live in a separate doc: `_setup_backend.md`.

---

## Phase 1 – Repository & Folder Structure

**What**
- Create two main project roots:
  - `skillbridge-backend/` – Spring Boot, modular monolith
  - `skillbridge-frontend/` – React + TypeScript

**Why**
- Matches our decision: **separate repositories** → independent CI/CD, clearer ownership.
- Keeps backend and frontend concerns clearly separated.
- Easier to deploy / scale each side independently later.

---

## Phase 2 – Backend Skeleton (Spring Boot Modular Monolith)

**What**
- Generate a Spring Boot project (`com.skillbridge` as base package).
- Add core dependencies:
  - Web, Security, JPA, Validation, Lombok, Actuator, PostgreSQL driver
  - (Later) Flyway/Liquibase for DB migrations
- Create modular package structure:

```text
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

**Why**
- This is a **modular monolith**: one deployable app, but internally divided into clear domains.
- Each module can later be extracted into its own microservice if needed.
- Keeps code organized and easier to navigate as the project grows.

---

## Phase 3 – Database (Supabase) & Migrations

**What**
- Create a **Supabase PostgreSQL** project (managed DB).
- Configure Spring Boot to connect to Supabase using JDBC.
- Add a migration tool (Flyway/Liquibase).
- Create initial migration:
  - `colleges`
  - `users`
  - `roles`, `user_roles`
  - `refresh_tokens`

**Why**
- Supabase gives us managed PostgreSQL: backups, monitoring, no infra overhead.
- Migrations ensure our schema is **versioned and reproducible** (no “it works on my DB”).
- Starting with the foundational tables prepares the ground for auth + multi-tenancy.

---

## Phase 4 – Common & Auth Modules

**What**
- In `common`:
  - `BaseEntity` (id, createdAt, updatedAt, etc.)
  - Global exception types + `@ControllerAdvice` for consistent error responses
  - Shared DTOs/utilities if needed
- In `auth`:
  - `User` entity (with `collegeId`, `email`, `password`, `role`, `active`)
  - `RefreshToken` entity
  - `AuthService`, `RefreshTokenService`
  - `JwtTokenProvider`, JWT filter, `SecurityConfig`
  - Endpoints: `/api/v1/auth/register`, `/login`, `/refresh`

**Why**
- **Authentication is the foundation**: everything else (RBAC, tenant isolation) depends on a known user and role.
- Reusable “common” pieces prevent duplication and give us a clean place for cross-cutting concerns.

---

## Phase 5 – Multi-Tenancy Plumbing

**What**
- Use `college_id` as the **tenant identifier**.
- Ensure all tenant-scoped tables have a `college_id` column.
- Add:
  - `TenantContext` utility class to read `collegeId` and role from the authenticated user.
  - Hibernate `@Filter` definitions to automatically apply `WHERE college_id = :collegeId` for tenant-scoped entities.
  - `TenantAwareRepository` base interface so all repositories respect tenant boundaries.
- Implement rules:
  - `SYSTEM_ADMIN` can bypass filters (cross-tenant).
  - All other roles are restricted to their `college_id`.

**Why**
- Enforces **data isolation** between colleges.
- Implements our decision: **application-level multi-tenancy** (no RLS at DB level initially).
- Centralizes tenant logic so we don’t duplicate checks everywhere.

---

## Phase 6 – Core Domain Modules

**What**
- Implement the main modules with basic CRUD and business logic:
  - `college` – manage colleges (system admin scope)
  - `student` – student profiles and skill profiles
  - `trainer` – trainer profiles
  - `batch` – batch lifecycle and enrollment
  - `progress` – topic-level progress tracking
  - `feedback` – feedback between trainer and student
  - `company` & `placement` – basic company and placement records

**Why**
- These are the **core features** of SkillBridge.
- Focusing here first gives us a working end‑to‑end platform before advanced features (recommendations, analytics).

---

## Phase 7 – Recommendation Engine (Rule-Based)

**What**
- Build the `recommendation` module:
  - Service that calculates a **score** per batch for a student:
    - Skill match
    - Learning opportunity
    - Company alignment
  - Configurable weights from `application.yml`.
  - API endpoint: `/api/v1/recommendations/batches?studentId=...`

**Why**
- Delivers one of the **key differentiators** of SkillBridge early (intelligent batch suggestions).
- Rule-based approach is easy to understand and evolve; later we can swap in ML if needed.

---

## Phase 8 – Frontend Skeleton (React + TypeScript)

**What**
- Initialize React + TypeScript app (via Vite or CRA).
- Add libraries: React Router, Axios, React Query, React Hook Form, MUI/AntD.
- Setup:
  - Authentication pages (login, register).
  - Axios client with JWT handling.
  - Role-based routing/layout (student, trainer, admin dashboards).
  - Minimal dashboard that calls secure backend endpoint.

**Why**
- Gives a visual interface so we can **see and test** our backend flows.
- Early integration reveals API design issues quickly.

---

## Phase 9 – Environments & Configuration

**What**
- Backend:
  - Profiles: `dev`, `prod`.
  - `application-dev.yml` and `application-prod.yml` (DB URLs, JWT secrets, etc.).
- Frontend:
  - `.env.development`, `.env.production` with `VITE_API_BASE_URL`.

**Why**
- Clean separation of development vs production settings.
- Makes deployment to cloud much easier (no code changes, only env vars).

---

## Phase 10 – Docker & Deployment Foundations

**What**
- Backend:
  - `Dockerfile` that builds and runs the Spring Boot jar.
- Frontend:
  - `Dockerfile` for building static assets (served by Nginx or similar).
- (Optional) `docker-compose.yml` for local multi-container setup.
- GitHub Actions:
  - Basic pipeline to build and test backend & frontend.

**Why**
- Containerization ensures **consistent environments** across dev, staging, and prod.
- CI pipelines catch issues early and prepare us for real deployment to a cloud VM/Kubernetes later.

---

## Where to Go for Details

- **Backend step‑by‑step guide**: see `_setup_backend.md`
- **Architecture decisions**: `01-architecture-decisions.md`
- **Tech stack explanations**: `02-tech-stack.md`
- **Multi-tenancy details**: `03-multi-tenancy.md`
- **Auth & security details**: `04-authentication-security.md`


