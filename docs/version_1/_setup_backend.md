## Backend Setup – Step by Step (Spring Boot Modular Monolith)

This guide walks you **step by step** through setting up the **SkillBridge backend** as a modular monolith.

For each step, we cover:
- **Goal** – what we want to achieve
- **What you do** – concrete actions
- **Why** – reasoning behind it

You can follow this like a checklist while building.

---

## Step 0 – Prerequisites

**Goal**: Make sure your environment is ready to run a modern Spring Boot app.

**What you need**
- **Java 17+** installed (`java -version`)
- **Maven** installed (`mvn -v`) – or use the Maven wrapper later
- A **Supabase** account (for PostgreSQL)
- An IDE (IntelliJ IDEA, VS Code with Java plugins, or Eclipse)

**Why**
- Spring Boot 3 officially targets Java 17+.
- Maven is the common build tool for Spring Boot.
- We’ll connect directly to Supabase’s PostgreSQL from the backend.

---

## Step 1 – Create the Spring Boot Project

**Goal**: Generate a clean Spring Boot project with the right dependencies.

### What you do

1. Go to the **Spring Initializr** (web UI or via IDE integration).
2. Use these settings:
   - **Project**: Maven
   - **Language**: Java
   - **Spring Boot**: 3.x
   - **Group**: `com.skillbridge`
   - **Artifact**: `skillbridge-backend`
   - **Name**: `SkillBridge Backend`
   - **Package Name**: `com.skillbridge`
   - **Java**: 17
3. Add dependencies:
   - **Spring Web** (REST APIs)
   - **Spring Security** (auth & RBAC)
   - **Spring Data JPA** (ORM)
   - **Validation** (input validation)
   - **PostgreSQL Driver** (DB connection)
   - **Lombok** (less boilerplate)
   - **Spring Boot Actuator** (health & monitoring)
   - (Optional now, but soon) **Flyway** (DB migrations)
4. Generate & download the project, then place it in `skillbridge-backend/`.

### Why

- Spring Initializr gives you a **minimal, correct** project structure.
- Adding these dependencies from the start saves refactoring later.
- Using `com.skillbridge` as the base package aligns with our architecture docs.

---

## Step 2 – Organize Packages as a Modular Monolith

**Goal**: Reflect our domain modules in the package structure from day one.

### What you do

Inside `src/main/java/com/skillbridge/`, create these packages:

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

In each domain package (later), you’ll typically have:
- `controller` – REST endpoints
- `service` – business logic
- `repository` – data access (JPA)
- `entity` – JPA entities
- `dto` – Data Transfer Objects
- `mapper` – entity ↔ DTO mapping

### Why

- A **modular monolith** keeps everything in one app but still enforces **clear boundaries**.
- It’s easier to:
  - Find code (e.g., all `student` logic in one place)
  - Extract a module into a microservice in the future, if needed.

---

## Step 3 – Connect to Supabase PostgreSQL

**Goal**: Make the backend talk to our managed Supabase Postgres database.

### What you do

1. In **Supabase**, create a new project.
2. Note the **connection details**:
   - Host
   - Port
   - Database name
   - User
   - Password
3. In `src/main/resources/`, create `application-dev.yml` (if not already using YAML).

Example:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://<SUPABASE_HOST>:5432/<DB_NAME>
    username: <SUPABASE_USER>
    password: <SUPABASE_PASSWORD>
  jpa:
    hibernate:
      ddl-auto: none      # We will use migrations instead
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  profiles:
    active: dev
```

4. Make sure the PostgreSQL driver dependency is present in `pom.xml`.

### Why

- Using Supabase gives us a **managed PostgreSQL** with minimal ops overhead.
- Setting `ddl-auto: none` enforces discipline to use **migrations**, avoiding “magical” schema changes.

---

## Step 4 – Introduce Database Migrations (Flyway)

**Goal**: Manage schema changes in a repeatable, version-controlled way.

### What you do

1. Add Flyway dependency in `pom.xml`:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

2. Create the migrations folder:
- `src/main/resources/db/migration/`

3. Add your first migration file:
- `V1__init_core_tables.sql`

Example content (simplified):

```sql
CREATE TABLE colleges (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    domain       VARCHAR(255),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    id           BIGSERIAL PRIMARY KEY,
    college_id   BIGINT, -- nullable for system admins
    email        VARCHAR(255) NOT NULL UNIQUE,
    password     VARCHAR(255) NOT NULL,
    role         VARCHAR(50)  NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_users_college
        FOREIGN KEY (college_id) REFERENCES colleges (id)
);

CREATE TABLE refresh_tokens (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    token_hash   VARCHAR(255) NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    revoked      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id)
);
```

4. Run the app once; Flyway should apply `V1__...` automatically.

### Why

- Flyway gives us:
  - **Versioned schema** – each change has a file and a version.
  - **Reproducibility** – any dev or environment can recreate the same DB.
  - **Rollback strategy** – we can add compensating migrations if needed.

---

## Step 5 – Create Common Base Classes

**Goal**: Avoid duplication for common entity fields and standardize timestamps.

### What you do

In `com.skillbridge.common.entity`, create `BaseEntity`:

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    // getters/setters
}
```

Enable JPA auditing in a config class:

```java
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
```

Now your entities can `extends BaseEntity` to get `id`, `createdAt`, `updatedAt` for free.

### Why

- Prevents copying the same fields into every entity.
- Automatically tracks when rows are created/updated → useful for debugging, analytics, and audits.

---

## Step 6 – Implement the User & Role Model (Auth Basics)

**Goal**: Have a basic user model that supports our roles and tenants.

### What you do

In `com.skillbridge.auth.entity`, create `User`:

```java
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(name = "college_id")
    private Long collegeId; // null for SYSTEM_ADMIN

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    // getters/setters
}
```

Create `Role` enum:

```java
public enum Role {
    SYSTEM_ADMIN,
    COLLEGE_ADMIN,
    TRAINER,
    STUDENT
}
```

Create `UserRepository`:

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

### Why

- This aligns directly with our **RBAC model** (4 roles).
- `collegeId` on the user is the key to **multi-tenancy** later (tenant = college).

---

## Step 7 – Password Hashing & Security Config Basics

**Goal**: Store passwords securely and set up the basic security pipeline.

### What you do

1. In a config class, define a `PasswordEncoder` bean:

```java
@Configuration
public class SecurityBeansConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
```

2. Use this encoder in user creation flows (later in `AuthService`).

3. Create the basic `SecurityConfig`:

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .anyRequest().authenticated()
            );

        return http.build();
    }
}
```

### Why

- BCrypt is a strong, battle-tested password hashing algorithm.
- Stateless sessions (`SessionCreationPolicy.STATELESS`) are needed for JWT-based auth.
- Opening only `/api/v1/auth/**` keeps other endpoints protected by default.

---

## Step 8 – JWT Support (Token Generation & Validation)

**Goal**: Issue and validate JWT access and refresh tokens.

### What you do

1. Add JWT properties in `application-dev.yml`:

```yaml
jwt:
  secret: your-very-strong-secret-key-here
  access-token-expiration-ms: 900000    # 15 minutes
  refresh-token-expiration-ms: 604800000 # 7 days
```

2. Implement `JwtTokenProvider` in `com.skillbridge.auth.jwt`:

```java
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    @Value("${jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    public String generateAccessToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
            .setSubject(user.getId().toString())
            .claim("email", user.getEmail())
            .claim("role", user.getRole().name())
            .claim("collegeId", user.getCollegeId())
            .setIssuedAt(now)
            .setExpiration(expiry)
            .signWith(SignatureAlgorithm.HS512, secret)
            .compact();
    }

    public String generateRefreshToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenExpirationMs);

        return Jwts.builder()
            .setSubject(user.getId().toString())
            .setIssuedAt(now)
            .setExpiration(expiry)
            .signWith(SignatureAlgorithm.HS512, secret)
            .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
            .setSigningKey(secret)
            .parseClaimsJws(token)
            .getBody();
    }
}
```

3. Create a `JwtAuthenticationFilter` (extending `OncePerRequestFilter`) to:
   - Read `Authorization: Bearer <token>` header
   - Validate token
   - Load user details
   - Put `Authentication` in `SecurityContextHolder`

4. Register this filter in `SecurityConfig` before `UsernamePasswordAuthenticationFilter`.

### Why

- JWT allows **stateless** auth: the server doesn’t store session data.
- Embedding `role` and `collegeId` inside the token enables **RBAC** and **multi-tenancy**.

---

## Step 9 – Auth APIs (Register, Login, Refresh)

**Goal**: Expose endpoints for authentication using our JWT mechanism.

### What you do

In `com.skillbridge.auth.controller`, create `AuthController`:

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`

Backed by `AuthService` that:
- Registers users (with proper role & college)
- Validates credentials
- Generates and returns tokens
- Stores/validates/revokes refresh tokens

Example simplified login flow:

```java
@PostMapping("/api/v1/auth/login")
public AuthResponse login(@RequestBody @Valid LoginRequest request) {
    User user = authService.authenticate(request.getEmail(), request.getPassword());
    String accessToken = jwtTokenProvider.generateAccessToken(user);
    String refreshToken = jwtTokenProvider.generateRefreshToken(user);
    refreshTokenService.saveRefreshToken(user.getId(), refreshToken);
    return new AuthResponse(accessToken, refreshToken);
}
```

### Why

- These endpoints form the **entry point** to the backend for all clients.
- Once this is working, we can protect all other APIs and trust the security context.

---

## Step 10 – Tenant Context & Multi-Tenancy Wiring

**Goal**: Make every request tenant-aware using `collegeId` from JWT.

### What you do

1. Create `TenantContext` helper in `com.skillbridge.tenant`:

```java
@Component
public class TenantContext {

    public Long getCurrentCollegeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        return principal.getCollegeId();
    }

    public boolean isSystemAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null &&
               auth.getAuthorities().stream()
                   .anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
    }
}
```

2. Add `college_id` columns to tenant-scoped tables in migrations.
3. Add Hibernate `@Filter` and `@FilterDef` annotations to tenant-scoped entities (e.g., `Student`, `Batch`).
4. Implement a base `TenantAwareRepository` that enables the filter for non–system-admin users.

### Why

- This centralizes tenant logic so we **never rely on client-sent `collegeId`**.
- All access is automatically scoped per college, except for `SYSTEM_ADMIN`.

---

## Step 11 – Verify with a Simple Protected Endpoint

**Goal**: Confirm our auth + multi-tenancy pipeline works end to end.

### What you do

1. Create a simple controller, e.g., `HealthController` in `common`:

```java
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of(
            "user", authentication.getName(),
            "authorities", authentication.getAuthorities()
        );
    }
}
```

2. Call `/api/v1/health/me`:
   - Without token → should get **401 Unauthorized**
   - With valid JWT → should see your username and roles

### Why

- This is a quick sanity check that:
  - Security filter chain works
  - JWT is parsed correctly
  - Authentication is populated in the context

---

## Next Steps After Backend Setup

Once all above steps are stable, we can:
- Build out the **domain modules** (college, student, batch, etc.).
- Design and implement the **recommendation** module.
- Start the **frontend setup** and integrate with these auth endpoints.

This document focuses only on **backend setup**. For the full project roadmap, see `00-complete-setup.md`.


