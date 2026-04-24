# Tech Stack Documentation

This document details the complete technology stack for SkillBridge, explaining each choice and how components work together.

---

## High-Level Stack Summary

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Frontend** | React + TypeScript | User interface |
| **Backend** | Spring Boot (Java) | Business logic & API |
| **API Style** | REST (JSON) | Communication protocol |
| **Database** | PostgreSQL (Supabase) | Data persistence |
| **ORM** | Spring Data JPA (Hibernate) | Object-relational mapping |
| **Authentication** | JWT + Refresh Tokens | User authentication |
| **Authorization** | RBAC (Spring Security) | Access control |
| **Multi-Tenancy** | Application-level (college_id) | Data isolation |
| **Caching** | Redis (future) | Performance optimization |
| **File Storage** | Supabase Storage | Document & image storage |
| **Deployment** | Docker + Cloud VM | Containerization |
| **CI/CD** | GitHub Actions | Automated deployment |
| **Monitoring** | Prometheus + Grafana (future) | Observability |

---

## Frontend Stack

### Core: React + TypeScript

**Why React?**
- **Component-Based**: Build reusable UI components
- **Ecosystem**: Massive library ecosystem (forms, charts, tables)
- **Performance**: Virtual DOM for efficient updates
- **Industry Standard**: Most popular frontend framework
- **Learning Resource**: Abundant tutorials and documentation

**Why TypeScript?**
- **Type Safety**: Catch errors at compile-time, not runtime
- **Better IDE Support**: Autocomplete, refactoring, navigation
- **Large Codebase**: TypeScript scales better than JavaScript
- **Team Collaboration**: Types serve as documentation

### Supporting Libraries

| Purpose | Library | Why |
|---------|---------|-----|
| **Routing** | React Router | Standard routing for React apps |
| **API Calls** | Axios | Promise-based HTTP client, better than fetch |
| **State Management** | React Query (TanStack Query) | Server state management, caching, refetching |
| **Forms** | React Hook Form | Performant forms with validation |
| **UI Components** | MUI (Material-UI) or Ant Design | Pre-built, professional components |
| **Charts** | Recharts | React-native charting library |
| **Authentication** | JWT stored in httpOnly cookies | Secure token storage |

### Frontend Architecture

```
src/
 ├── auth/           # Authentication components & logic
 ├── students/       # Student-specific pages & components
 ├── trainers/       # Trainer-specific pages & components
 ├── admins/         # Admin pages (college & system)
 ├── batches/        # Batch management UI
 ├── companies/      # Company browsing & management
 ├── placements/     # Placement tracking UI
 ├── feedback/       # Feedback submission & viewing
 ├── shared/         # Shared components, utilities, types
 │   ├── components/ # Reusable UI components
 │   ├── hooks/      # Custom React hooks
 │   ├── utils/      # Helper functions
 │   └── types/      # TypeScript type definitions
 └── api/            # API client functions
     ├── client.ts   # Axios instance with interceptors
     ├── auth.ts     # Auth-related API calls
     ├── students.ts # Student API calls
     └── ...
```

**Why This Structure?**
- **Feature-Based**: Each feature has its own folder
- **Shared Code**: Common components in `shared/`
- **API Layer**: Centralized API calls in `api/`
- **Scalable**: Easy to add new features

---

## Backend Stack

### Core: Spring Boot (Java)

**Why Spring Boot?**
- **Enterprise-Grade**: Battle-tested framework used by Fortune 500 companies
- **Rapid Development**: Auto-configuration, embedded server, starter dependencies
- **Ecosystem**: Spring Security, Spring Data JPA, Spring Cloud
- **Multi-Tenancy**: Excellent support for tenant isolation
- **Transaction Management**: Declarative transactions with `@Transactional`
- **Validation**: Built-in Bean Validation (JSR-303)
- **Testing**: Excellent testing support (JUnit, Mockito, TestContainers)

**Key Spring Boot Features We'll Use:**
1. **Spring Security**: Authentication & authorization
2. **Spring Data JPA**: Database access
3. **Spring Validation**: Input validation
4. **Spring AOP**: Cross-cutting concerns (logging, transactions)
5. **Spring Actuator**: Health checks, metrics (for monitoring)

### Backend Architecture (Modular Monolith)

```
com.skillbridge
 ├── auth/              # Authentication & authorization
 │   ├── controller/    # REST endpoints
 │   ├── service/        # Business logic
 │   ├── repository/     # Data access
 │   ├── entity/         # Domain models
 │   └── dto/            # Data transfer objects
 ├── tenant/             # Multi-tenancy filters & utilities
 ├── college/            # College management
 ├── student/            # Student domain
 ├── trainer/            # Trainer domain
 ├── batch/              # Batch management
 ├── syllabus/           # Syllabus & topics
 ├── progress/           # Progress tracking
 ├── feedback/           # Feedback system
 ├── company/            # Company management
 ├── placement/          # Placement tracking
 ├── recommendation/     # Batch recommendation engine
 ├── reporting/          # Analytics & reports
 └── common/             # Shared utilities, exceptions, configs
     ├── exception/      # Custom exceptions
     ├── config/         # Configuration classes
     ├── security/       # Security configuration
     └── util/           # Utility classes
```

**Why Modular Monolith?**
- **Single Deployment**: Easier than microservices initially
- **Clear Boundaries**: Modules are separated (can extract to services later)
- **Shared Database**: Simpler transactions across modules
- **Future-Proof**: Can split into microservices when needed
- **Team Learning**: Easier to understand than microservices

**Module Structure (Example: `student` module):**
```java
com.skillbridge.student
 ├── StudentController      # REST API endpoints
 ├── StudentService         # Business logic
 ├── StudentRepository      # Data access (Spring Data JPA)
 ├── Student                # Entity (JPA)
 ├── StudentDTO             # Data transfer object
 └── StudentMapper          # Entity ↔ DTO conversion
```

---

## Database Stack

### PostgreSQL (via Supabase)

**Why PostgreSQL?**
- **ACID Compliance**: Reliable transactions
- **JSON Support**: Can store flexible data structures
- **Excellent Indexing**: Fast queries with proper indexes
- **Mature**: Battle-tested, 30+ years of development
- **Open Source**: No licensing costs
- **Rich Ecosystem**: Tools, drivers, extensions

**Why Supabase (Managed PostgreSQL)?**
- **Managed Service**: Backups, monitoring, upgrades handled
- **Developer Experience**: Great dashboard, easy connection
- **Free Tier**: Perfect for development
- **Migration Path**: Can export and move to self-hosted later

**Connection:**
- We connect using standard PostgreSQL JDBC driver
- Connection string: `jdbc:postgresql://host:port/database`
- Spring Boot auto-configures connection pool (HikariCP)

---

## ORM: Spring Data JPA

### What is JPA?
**JPA (Java Persistence API)** is a specification for managing relational data in Java applications.

**What is Hibernate?**
**Hibernate** is the most popular JPA implementation. Spring Data JPA uses Hibernate under the hood.

### How It Works

**1. Entity Definition:**
```java
@Entity
@Table(name = "students")
public class Student {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "college_id", nullable = false)
    private Long collegeId;
    
    private String name;
    private String email;
    
    // Getters, setters, constructors
}
```

**2. Repository Interface:**
```java
public interface StudentRepository extends JpaRepository<Student, Long> {
    // Spring Data JPA automatically implements this!
    List<Student> findByCollegeId(Long collegeId);
    
    // Custom query
    @Query("SELECT s FROM Student s WHERE s.email = :email")
    Optional<Student> findByEmail(@Param("email") String email);
}
```

**3. Service Layer:**
```java
@Service
public class StudentService {
    @Autowired
    private StudentRepository studentRepository;
    
    public Student createStudent(StudentDTO dto) {
        Student student = new Student();
        student.setName(dto.getName());
        student.setEmail(dto.getEmail());
        return studentRepository.save(student); // INSERT
    }
    
    public List<Student> getStudentsByCollege(Long collegeId) {
        return studentRepository.findByCollegeId(collegeId); // SELECT
    }
}
```

**Benefits:**
- **No SQL Writing**: JPA generates SQL automatically
- **Type Safety**: Compile-time checking
- **Relationships**: Easy to define one-to-many, many-to-one, etc.
- **Transactions**: Automatic transaction management
- **Caching**: Hibernate first-level cache

---

## Authentication: JWT

### What is JWT?
**JWT (JSON Web Token)** is a compact, URL-safe token format for securely transmitting information.

### JWT Structure

A JWT has three parts separated by dots (`.`):
```
header.payload.signature
```

**Example:**
```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

**1. Header:**
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

**2. Payload (Claims):**
```json
{
  "sub": "1234567890",        // User ID
  "email": "student@college.edu",
  "role": "STUDENT",
  "collegeId": 1,
  "iat": 1516239022,          // Issued at
  "exp": 1516242622           // Expiration
}
```

**3. Signature:**
```
HMACSHA256(
  base64UrlEncode(header) + "." + base64UrlEncode(payload),
  secret
)
```

### Authentication Flow

```
1. User Login
   POST /api/v1/auth/login
   { "email": "student@college.edu", "password": "..." }
   
2. Server Validates Credentials
   - Check email/password in database
   - If valid, generate JWT token
   
3. Server Returns Tokens
   {
     "accessToken": "eyJhbGci...",
     "refreshToken": "eyJhbGci...",
     "expiresIn": 900  // 15 minutes
   }
   
4. Client Stores Tokens
   - Access token: In memory or httpOnly cookie
   - Refresh token: In httpOnly cookie (more secure)
   
5. Client Sends Token with Requests
   Authorization: Bearer eyJhbGci...
   
6. Server Validates Token
   - Verify signature
   - Check expiration
   - Extract user info from payload
   
7. When Access Token Expires
   POST /api/v1/auth/refresh
   { "refreshToken": "..." }
   
8. Server Returns New Access Token
```

### Why JWT?
- **Stateless**: No server-side session storage needed
- **Scalable**: Works across multiple servers
- **Self-Contained**: User info embedded in token
- **Standard**: Industry-standard format

---

## Authorization: RBAC (Role-Based Access Control)

### Roles in SkillBridge

| Role | Scope | Example Permissions |
|------|-------|---------------------|
| **SYSTEM_ADMIN** | All colleges | Create colleges, view platform metrics |
| **COLLEGE_ADMIN** | One college | Create batches, manage students |
| **TRAINER** | Assigned batches | Update student progress, view batch students |
| **STUDENT** | Own data | View own progress, apply to batches |

### Implementation with Spring Security

**1. Method-Level Security:**
```java
@PreAuthorize("hasRole('COLLEGE_ADMIN')")
public Batch createBatch(BatchDTO dto) {
    // Only COLLEGE_ADMIN can call this
}

@PreAuthorize("hasRole('TRAINER') and @batchService.isAssignedToBatch(authentication.name, #batchId)")
public void updateProgress(Long batchId, Long studentId, ProgressDTO dto) {
    // Only trainers assigned to this batch can update
}
```

**2. Resource-Level Security:**
```java
// Automatically filter by college_id from JWT
@Filter(name = "collegeFilter")
public class Student {
    // Only students from the same college are returned
}
```

**3. Custom Security Expressions:**
```java
@PreAuthorize("@securityService.canAccessStudent(#studentId)")
public Student getStudent(Long studentId) {
    // Custom logic: student can only access their own data
    // Trainer can access students in their batches
    // Admin can access all students in their college
}
```

---

## API Design: REST

### REST Principles

**REST (Representational State Transfer)** is an architectural style for designing web services.

**Key Principles:**
1. **Resources**: Everything is a resource (student, batch, etc.)
2. **HTTP Methods**: Use appropriate HTTP verbs
3. **Stateless**: Each request contains all information needed
4. **Uniform Interface**: Consistent URL patterns

### HTTP Methods

| Method | Purpose | Example |
|--------|---------|---------|
| **GET** | Retrieve resource(s) | `GET /api/v1/students` |
| **POST** | Create resource | `POST /api/v1/students` |
| **PUT** | Update entire resource | `PUT /api/v1/students/1` |
| **PATCH** | Partial update | `PATCH /api/v1/students/1` |
| **DELETE** | Delete resource | `DELETE /api/v1/students/1` |

### URL Patterns

```
# Collections
GET    /api/v1/students              # List all students
POST   /api/v1/students              # Create student

# Individual Resources
GET    /api/v1/students/{id}         # Get student
PUT    /api/v1/students/{id}         # Update student
DELETE /api/v1/students/{id}         # Delete student

# Nested Resources
GET    /api/v1/batches/{id}/students  # Get students in batch
POST   /api/v1/batches/{id}/enroll   # Enroll in batch

# Actions
POST   /api/v1/batches/{id}/activate  # Activate batch
POST   /api/v1/feedback               # Submit feedback
```

### Response Format

**Success Response:**
```json
{
  "data": { ... },
  "message": "Student created successfully"
}
```

**Error Response:**
```json
{
  "error": {
    "code": "STUDENT_NOT_FOUND",
    "message": "Student with ID 123 not found",
    "timestamp": "2024-01-15T10:30:00Z"
  }
}
```

**List Response:**
```json
{
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "size": 20,
    "total": 100,
    "totalPages": 5
  }
}
```

---

## File Storage: Supabase Storage

### What is Supabase Storage?
Supabase Storage is an S3-compatible object storage service.

### Usage

**Upload File:**
```java
// Backend: Generate signed URL
String uploadUrl = supabaseStorage.generateUploadUrl("resumes", fileName);

// Frontend: Upload to signed URL
await fetch(uploadUrl, {
  method: 'PUT',
  body: file
});
```

**Access File:**
```java
// Backend: Generate public URL
String fileUrl = supabaseStorage.getPublicUrl("resumes", fileName);
```

### Why Supabase Storage?
- **S3-Compatible**: Can migrate to AWS S3 later
- **Integrated**: Already using Supabase
- **Simple**: No additional infrastructure
- **Secure**: Access control built-in

---

## Development Tools

### Backend
- **Maven**: Dependency management & build tool
- **Lombok**: Reduces boilerplate code
- **MapStruct**: Entity ↔ DTO mapping
- **Spring Boot DevTools**: Hot reload during development

### Frontend
- **Vite**: Fast build tool (alternative to Create React App)
- **ESLint**: Code linting
- **Prettier**: Code formatting
- **TypeScript**: Type checking

### Database
- **Flyway** or **Liquibase**: Database migration tool
- **pgAdmin**: PostgreSQL administration (optional)

---

## Deployment

### Docker

**Why Docker?**
- **Consistency**: Same environment in dev, staging, production
- **Isolation**: Application runs in container
- **Portability**: Run anywhere Docker runs
- **Easy Scaling**: Spin up multiple instances

**Dockerfile Example:**
```dockerfile
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY target/skillbridge-backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### CI/CD: GitHub Actions

**Workflow:**
1. Push code to GitHub
2. GitHub Actions runs tests
3. Build Docker image
4. Push to container registry
5. Deploy to cloud VM

---

## Monitoring (Future)

### Prometheus + Grafana

**Prometheus**: Metrics collection
**Grafana**: Visualization dashboard

**Metrics to Track:**
- Request rate
- Response time
- Error rate
- Database query performance
- JVM memory usage

---

## Summary

This tech stack provides:
- **Type Safety**: TypeScript + Java
- **Rapid Development**: Spring Boot + React
- **Scalability**: Stateless JWT, modular architecture
- **Maintainability**: Clear structure, good practices
- **Future-Proof**: Can evolve to microservices, add ML, etc.

