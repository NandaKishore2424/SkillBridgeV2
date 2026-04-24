# SkillBridge Codebase Deep Dive

## 1. Project in One Sentence
SkillBridge is a multi-tenant training management platform for colleges that centralizes authentication, student and trainer profiles, batch management, curriculum planning, enrollment, feedback, and bulk onboarding in a single Spring Boot + React system.

## 2. What Problem This Solves
The platform replaces the common college workflow of spreadsheets, WhatsApp messages, manual follow-ups, and disconnected documents. It gives each college a private workspace while still running on one shared application and one shared database.

The real business goals are:
- keep college data isolated
- manage batches and trainers in one place
- onboard students and trainers faster
- track progress, feedback, and curriculum clearly
- support admin, trainer, and student roles with different experiences

## 3. High-Level Architecture
SkillBridge is a classic full-stack application with a Spring Boot backend and a React frontend.

### Backend
The backend is a Spring Boot 3.5.8 application written in Java 17. It uses:
- Spring Web for REST APIs
- Spring Data JPA and Hibernate for persistence
- Spring Security for authentication and authorization
- Flyway for database migrations
- PostgreSQL as the relational database
- Lombok to reduce boilerplate
- OpenCSV for CSV parsing during bulk uploads
- Actuator for health/ops endpoints

### Frontend
The frontend is a React 19 + TypeScript app built with Vite. It uses:
- React Router for route handling
- TanStack Query for server state and caching
- Axios for API calls
- React Hook Form and Zod for forms and validation
- Shadcn UI and Tailwind CSS for the interface
- Sonner for toast notifications

### Data Flow
The frontend talks to the backend through REST endpoints. The backend uses repositories and services to talk to PostgreSQL. Authentication is token-based, and the frontend stores access and refresh tokens locally.

## 4. Main Roles
The system is built around four roles:
- SYSTEM_ADMIN: platform-level control across all colleges
- COLLEGE_ADMIN: manages one college's students, trainers, batches, companies, and uploads
- TRAINER: handles assigned batches, curriculum, sessions, feedback, and student guidance
- STUDENT: views dashboard, profile, batches, progress, and feedback

## 5. Repository Structure
The codebase is split into two main apps:
- [skillbridge-backend](skillbridge-backend)
- [skillbridge-frontend](skillbridge-frontend)

The backend is organized by domain, which makes it easy to reason about features:
- auth
- college
- student
- trainer
- batch
- company
- enrollment
- feedback
- syllabus
- timeline
- bulkupload
- common/shared configuration

The frontend is organized by route and feature area:
- pages for role-based screens
- api modules for each backend domain
- shared layout and auth guards
- reusable UI components
- feature components like batch management dialogs and curriculum tabs

## 6. Backend Tech Stack
### Spring Boot 3.5.8
Used as the application framework. It gives the project:
- clean REST API structure
- dependency injection
- validation support
- actuator endpoints
- transaction handling
- easy integration with JPA and security

### Java 17
Used for the backend language. The project benefits from:
- strong typing
- stable language features
- good tool support
- reliable enterprise ecosystem

### Spring Data JPA + Hibernate
Used for object-relational mapping.
- Entities map to database tables
- Relationships are modeled with `@OneToOne`, `@ManyToOne`, and `@ManyToMany`
- Repository interfaces generate queries from method names
- Lazy loading is used in many places to avoid loading unnecessary data

### Spring Security
Used for auth and route protection.
- Stateless session policy
- Authorization filter before the username/password filter
- CORS configured for local frontend origins
- Method security enabled for role-based access

### Flyway
Used to version and execute database migrations automatically.
- Every schema change is tracked as a numbered SQL file
- The app can create and evolve the schema consistently across environments

### PostgreSQL
Used because the domain is relational and heavily connected.
- colleges own users, batches, trainers, students, and companies
- students have skills and projects
- batches have trainers, companies, curriculum, timeline sessions, and enrollments
- feedback and uploads need audit-friendly storage

### OpenCSV
Used to parse CSV files for bulk student and trainer onboarding.

### Lombok
Used in entities, DTOs, and services to reduce getters, setters, constructors, and builders.

## 7. Frontend Tech Stack
### React 19
Used for the UI layer. The app is component-based and route-driven.

### TypeScript
Used to make data contracts safer across the frontend.
- DTOs and API responses are typed
- changes in backend payloads are easier to catch early
- role and user objects are easier to manage safely

### Vite
Used as the build and dev server tool.

### React Router
Used to define public routes and protected role-based routes.

### TanStack Query
Used for server state.
- caching
- background refetching
- loading and error state management
- less manual state handling than raw `useEffect`

### Axios
Used for HTTP requests and token handling.

### React Hook Form + Zod
Used for forms with validation.

### Shadcn UI + Tailwind CSS
Used for the component system and styling.
- accessible primitives
- utility-first styling
- consistent layout primitives
- a clean admin-style interface

## 8. Authentication and Security Model
Authentication is currently token-based. It is not a full JWT implementation yet, but the architecture is already aligned with a future JWT upgrade.

### Current Login Flow
1. user logs in with email and password
2. backend checks the hashed password
3. backend generates a simple access token and refresh token string
4. backend returns user details with role and college context
5. frontend stores tokens and user info in localStorage
6. backend reads the Authorization header and loads the user into the security context

### Token Format
The current access token is simple and readable, like `token_{userId}_{timestamp}`. The refresh token is `refresh_{userId}`.

### Why This Exists
This was likely used to keep the project simple during development while still supporting the expected token-based flow on the frontend.

### Security Pieces
- BCrypt password hashing
- stateless session policy
- request filtering with a custom token filter
- role-based access control at both route and method level
- CORS configured for local development origins

### Important Security Constraints
- `users.email` is unique
- inactive users cannot log in
- first-login flow can force password reset
- refresh token handling is built into the auth service flow

## 9. Multi-Tenancy Design
This is one of the most important concepts in the project.

### Core Idea
Each college is a tenant.
- colleges are stored in the `colleges` table
- tenant-scoped data carries `college_id`
- one college should not see another college's records

### Tables That Use `college_id`
- users
- college_admins
- trainers
- students
- batches
- companies
- bulk_uploads

### Tables That Are Global
- roles
- skills
- system-level auth support tables
- lookup/reference tables that are shared across tenants

### Why This Is a Good Design
- one codebase can serve many colleges
- lower infra cost than separate apps
- easier operations and maintenance
- a clean tenant boundary for access control

### How It Is Enforced
The project primarily uses application-level isolation.
- backend queries are written with college context in mind
- authenticated users carry college data in their profile/token context
- routes are grouped by role, so the UI also reflects tenancy

## 10. Database Design Summary
The database is normalized and built around a few core ideas:
- single users table for authentication identity
- separate profile tables for students, trainers, and college admins
- junction tables for many-to-many relationships
- audit timestamps on most tables
- unique constraints for ordered content and deduplication
- indexes on foreign keys and frequent filter columns

## 11. Core Tables and Relationships
### Colleges
Tenant root table.
- id
- name
- code
- contact details
- status
- timestamps

### Users
Authentication identity table.
- id
- college_id
- email
- password_hash
- is_active
- account management columns like must_change_password, account_status, invitation_sent_at, first_login_at, profile_completed

### Roles and User Roles
Users can have one or more roles through a many-to-many join table.

### College Admins
Profile table for college administrators.
- one-to-one with users
- belongs to a college

### Trainers
Profile table for trainers.
- one-to-one with users
- belongs to a college
- includes specialization, department, bio, phone, LinkedIn, and experience fields

### Students
Profile table for students.
- one-to-one with users
- belongs to a college
- includes roll number, degree, branch, year, phone, portfolio, GitHub, resume, and bio

### Skills
Global skill catalog.
- Java
- Python
- React
- Spring Boot
- databases
- DevOps
- cloud tools
- computer science concepts

### Student Skills
Junction table that stores skill proficiency from 1 to 5.
This is useful because it does not just say what a student knows, it also says how well they know it.

### Student Projects
Stores student portfolio work.
- title
- description
- technologies
- project URL
- GitHub URL
- duration

### Batches
Core training entity.
- college_id
- name
- description
- status
- start/end dates
- trainers many-to-many
- companies many-to-many

### Companies
Represents placement or hiring companies associated with a college and batch ecosystem.
- college_id
- name
- domain
- hiring type

### Enrollments
Many-to-many relationship between students and batches.
It stores who is enrolled in what batch and when.

### Enrollment Requests
Trainer-driven request workflow for adding or removing students from batches.
- request type: ADD or REMOVE
- status: PENDING, APPROVED, REJECTED
- reviewer tracking

### Feedbacks
Represents trainer-to-student and student-to-trainer feedback.
- batch scoped
- rating
- category
- comments
- feedback type

### Bulk Uploads
Tracks upload jobs for onboarding students or trainers.
- college context
- uploaded by user
- entity type
- status
- row counts
- error report

### Bulk Upload Results
Tracks each row result for audit and error analysis.
- row number
- status
- entity id when successful
- error message
- row data snapshot

### Syllabus Modules
Top level curriculum block for a batch.
- ordered by display order
- can have date range
- contains submodules

### Syllabus Submodules
Second level curriculum block.
- belongs to a module
- supports week number and date range
- contains topics

### Syllabus Topics
Smallest curriculum unit.
- belongs to a submodule
- completion tracking
- display order
- completedAt timestamp

### Batch Timeline Sessions
Session-based schedule attached to a batch.
- session number must be unique per batch
- optional link to syllabus topic
- planned date support

## 12. Important Database Constraints and Indexes
The database schema does more than store data. It actively protects data quality.

### Uniqueness Rules
- college code is unique
- user email is unique
- role name is unique
- student roll number is unique within a college
- batch display order is unique within a batch
- submodule display order is unique within a module
- topic display order is unique within a submodule
- timeline session number is unique within a batch
- student skill combination is unique through composite keys
- enrollments are unique per batch/student pair

### Status Checks
The schema uses check constraints to limit invalid values such as:
- college status ACTIVE/INACTIVE
- batch status UPCOMING/OPEN/ACTIVE/COMPLETED/CANCELLED
- feedback rating between 1 and 5
- student skill proficiency between 1 and 5
- request status PENDING/APPROVED/REJECTED

### Indexing Strategy
Indexes are placed on:
- foreign keys
- status columns
- college-scoped lookups
- date fields used for dashboards and timelines
- bulk upload audit fields
- search-heavy fields like roll number, email, and names

This helps the app stay responsive when data grows.

## 13. Flyway Migration Strategy
The database schema is managed through versioned SQL migrations.

### Why Flyway Is Used
- schema changes are reproducible
- development and production can stay aligned
- migrations are version controlled
- the schema can be rebuilt from scratch reliably

### Migration Pattern in This Project
- V1 initializes the core platform tables
- V2/V3 add student and trainer detail tables
- later migrations add account management, bulk upload, timeline, enrollment, and curriculum hierarchy
- V15 introduces the submodule hierarchy and removes the older timeline concept from curriculum structure

### Why This Matters
Interviewers often care whether you understand schema evolution. This project shows that the database was not built as one giant script; it was evolved in steps.

## 14. Business Modules in the Backend
### Auth Module
Handles login, token refresh, password change, and first-login password setup.

### College Module
Supports platform-level college management and public college lookup for registration flows.

### Student Module
Handles student profile, dashboard, batches, recommendations, and progress-related DTOs.

### Trainer Module
Handles trainer profile, batch access, dashboards, and batch/student views.

### Batch Module
Core batch CRUD and relationships.

### Company Module
Tracks company profiles and their association with colleges.

### Enrollment Module
Handles batch enrollments and trainer-driven enrollment requests.

### Feedback Module
Tracks two-way feedback between trainers and students.

### Syllabus Module
Handles curriculum management with modules, submodules, and topics.

### Timeline Module
Manages batch sessions and scheduling.

### Bulk Upload Module
Imports students and trainers from CSV, writes results, and tracks errors row by row.

## 15. Frontend Feature Map
The frontend mirrors the backend domains.

### Public Pages
- landing page
- login
- register

### System Admin Pages
- system admin dashboard
- colleges list
- create college
- college detail
- create college admin

### College Admin Pages
- dashboard
- batches list
- create batch
- batch details
- companies list
- create company
- trainers list
- create trainer
- students list
- upload students
- upload trainers

### Trainer Pages
- trainer dashboard
- trainer batches
- batch details
- trainer students
- feedback management

### Student Pages
- student dashboard
- profile setup
- student feedback

## 16. Frontend Routing and Protection
The route setup is explicit and role-driven.

### ProtectedRoute
The app uses a route guard that:
- waits for auth initialization
- redirects unauthenticated users to login
- renders content only when authenticated

### RoleGuard
There is also a component-level role guard for UI sections.
This is useful when a page is accessible but some parts should only appear to certain roles.

### Why This Matters
It shows separation between:
- route access control
- UI feature visibility
- backend authorization

## 17. Important Algorithms and Logic
This project has more than CRUD. These are the real logic pieces.

### 1. Ordered Curriculum Hierarchy
The curriculum is a three-level structure:
- module
- submodule
- topic

The hierarchy is built with ordered display fields and uniqueness constraints. This prevents duplicate ordering and keeps the curriculum predictable.

### 2. Curriculum Conflict Detection
Before saving a module or submodule, the service checks whether another item already uses the same display order in the same parent scope. That prevents broken UI ordering.

### 3. Topic Completion Toggle
Topics can be marked complete or incomplete, and the service updates the completion timestamp accordingly. This supports progress tracking at a granular level.

### 4. Timeline Session Ordering
Every batch session has a session number, and that number must be unique inside a batch. This keeps the schedule ordered and prevents duplicate sessions.

### 5. Bulk Upload Partial Success Processing
Bulk upload does not fail the whole file when one row is bad.
- each row is validated independently
- success and failure counts are tracked
- each failed row gets an audit record
- the final response includes row-level errors

This is a strong real-world design choice because enterprise imports often have mixed good and bad rows.

### 6. First Login Password Reset Flow
A user created through bulk upload starts with a temporary password and a must-change-password flag. On first login, the user must set a new password before becoming fully active.

### 7. Feedback Routing by Feedback Type
Feedback can be either student-to-trainer or trainer-to-student. The service decides whether student and trainer references are required based on the feedback type.

### 8. Student Dashboard Filtering
Available batches are filtered by college and by open/active status, which keeps the student experience relevant and tenant-safe.

### 9. Token-Based Auth with Refresh
The frontend keeps access and refresh tokens, refreshes on expiry, and redirects users based on role after login.

## 18. What Looks Implemented vs What Looks Like a Stub
This is important if you talk to an interviewer honestly.

### Clearly Implemented
- auth/login/logout style flows
- college, batch, student, trainer, and company entities
- bulk upload of students and trainers
- curriculum modules/submodules/topics
- timeline sessions
- feedback system
- enrollment and enrollment requests
- frontend role-based routing and pages

### Partially Implemented or Placeholder-Like
- student recommendation logic currently returns an empty list in the service
- student dashboard stats are currently placeholders in the service
- some progress and enrollment-related flows look scaffolded for future completion

That does not reduce the quality of the project. It actually shows good architecture, because the DTOs, routes, and services are already prepared for those features.

## 19. Concepts You Can Mention in an Interview
These are the technical concepts behind the project.

### Architecture Concepts
- layered architecture
- separation of concerns
- domain-driven folder grouping
- REST API design
- role-based access control
- tenant-based data separation
- stateless authentication

### Database Concepts
- relational modeling
- foreign keys
- composite keys
- junction tables
- one-to-one / many-to-one / many-to-many relations
- indexed query design
- migration-based schema evolution

### Backend Concepts
- service layer abstraction
- DTO mapping
- transactional boundaries
- validation
- exception handling
- secure password hashing
- file import processing

### Frontend Concepts
- protected routes
- role-based navigation
- server state caching
- typed API contracts
- reusable UI primitives
- form validation

## 20. Suggested Interview Explanation
If someone asks what you built, you can say something like this:

"I built SkillBridge, a multi-tenant training management platform for colleges. The idea came from seeing how colleges manage training and placements using spreadsheets and scattered communication. I designed it so each college gets isolated data while still sharing the same platform. On the backend I used Spring Boot, Java 17, JPA, Flyway, and PostgreSQL. On the frontend I used React, TypeScript, TanStack Query, Axios, Tailwind, and Shadcn UI. The key features are role-based dashboards, batch and trainer management, curriculum planning with modules and submodules, timeline sessions, feedback, and CSV bulk uploads for onboarding students and trainers. I also designed the schema carefully with constraints, indexes, and migration scripts so the system stays maintainable as it grows."

## 21. Short Technical Highlights
- Spring Boot 3.5.8 backend
- Java 17
- React 19 + TypeScript frontend
- PostgreSQL with Flyway migrations
- token-based auth with refresh flow
- multi-tenant college-based data isolation
- curriculum hierarchy with module → submodule → topic
- bulk upload with row-level audit tracking
- feedback and enrollment workflows
- role-based route protection in the frontend

## 22. File Pointers Worth Knowing
If you want to quickly navigate the codebase, start here:
- [backend build config](skillbridge-backend/pom.xml)
- [frontend build config](skillbridge-frontend/package.json)
- [app routing](skillbridge-frontend/src/App.tsx)
- [auth flow](skillbridge-backend/src/main/java/com/skillbridge/auth/service/AuthService.java)
- [security filter](skillbridge-backend/src/main/java/com/skillbridge/auth/filter/TokenAuthenticationFilter.java)
- [multi-tenancy notes](docs/03-multi-tenancy.md)
- [database schema notes](docs/07-database-schema.md)
- [flyway notes](docs/08-flyway-migrations-explained.md)
- [curriculum service](skillbridge-backend/src/main/java/com/skillbridge/syllabus/service/SyllabusService.java)
- [timeline service](skillbridge-backend/src/main/java/com/skillbridge/timeline/service/TimelineService.java)
- [bulk upload service](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/service/BulkUploadService.java)

## 23. Final Takeaway
SkillBridge is not just a CRUD app. It is a structured SaaS-style college platform with tenant separation, role-based workflows, normalized data modeling, ordered curriculum planning, import automation, and a frontend built around real business roles. That makes it a strong project to discuss in interviews because you can explain both product thinking and practical engineering.
