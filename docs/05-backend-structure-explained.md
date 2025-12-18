# Backend Structure Explained - Complete Guide

This document explains **every part** of the SkillBridge backend structure, what it means, and how it all works together.

---

## 📁 Complete Project Structure

```
skillbridge-backend/
├── pom.xml                          ← Maven configuration (dependencies)
├── mvnw / mvnw.cmd                  ← Maven wrapper (build tool)
├── HELP.md                          ← Spring Boot help file
├── SETUP_PROGRESS.md                ← Our progress tracker
│
└── src/                             ← Source code directory
    ├── main/                        ← Main application code
    │   ├── java/                    ← Java source files
    │   │   └── com/skillbridge/    ← Package root
    │   │       ├── SkillbridgeBackendApplication.java  ← Entry point
    │   │       ├── auth/            ← Authentication module
    │   │       ├── common/          ← Shared utilities
    │   │       ├── student/         ← Student domain
    │   │       └── ... (other modules)
    │   │
    │   └── resources/               ← Configuration & static files
    │       ├── application.yaml     ← App configuration
    │       ├── db/migration/        ← SQL migration files
    │       ├── static/              ← Static files (CSS, JS, images)
    │       └── templates/           ← HTML templates (if needed)
    │
    └── test/                        ← Test code
        └── java/                    ← Test Java files
            └── com/skillbridge/
                └── SkillbridgeBackendApplicationTests.java
```

---

## 🔍 Part 1: Root Level Files

### `pom.xml` - The Heart of Maven Projects

**What it is:**
- **Maven Project Object Model** - XML file that defines your project
- Think of it as `package.json` in Node.js or `requirements.txt` in Python

**What it contains:**
1. **Project Info**: Name, version, description
2. **Dependencies**: All libraries your project needs
3. **Build Configuration**: How to compile and package your code

**Key Sections Explained:**

```xml
<groupId>com.skillbridge</groupId>
<artifactId>skillbridge-backend</artifactId>
<version>0.0.1-SNAPSHOT</version>
```
- **groupId**: Your organization/package namespace
- **artifactId**: Project name (becomes JAR filename)
- **version**: Current version (SNAPSHOT = development)

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    ...
</dependencies>
```
- Lists all libraries your project uses
- Maven automatically downloads them from Maven Central Repository

**Why it matters:**
- Without `pom.xml`, Maven doesn't know what to build or what dependencies to download
- This is where we declared: Web, Security, JPA, PostgreSQL, etc.

---

### `mvnw` / `mvnw.cmd` - Maven Wrapper

**What it is:**
- **Maven Wrapper** - A script that ensures everyone uses the same Maven version
- `mvnw` = Linux/Mac, `mvnw.cmd` = Windows

**Why it exists:**
- **Problem**: Different developers might have different Maven versions
- **Solution**: Wrapper downloads the correct Maven version automatically

**How you use it:**
```bash
# Instead of: mvn spring-boot:run
./mvnw spring-boot:run          # Linux/Mac
mvnw.cmd spring-boot:run       # Windows
```

**Teaching Point:**
- This ensures **consistent builds** across all machines
- No need to install Maven separately (wrapper handles it)

---

## 🔍 Part 2: Source Code Structure (`src/`)

### `src/main/java/` - Your Java Code Lives Here

**Why this structure?**
- **Maven Convention**: Maven expects code in `src/main/java/`
- **Standard Practice**: All Java projects follow this pattern
- **Separation**: Keeps source code separate from tests and resources

---

### `src/main/java/com/skillbridge/` - Package Root

**What is a package?**
- **Package** = Folder structure that organizes Java classes
- **Purpose**: Prevents naming conflicts, organizes code

**Example:**
```
com.skillbridge.auth.User
com.skillbridge.student.Student
```
- Full class name includes package: `com.skillbridge.auth.User`
- This prevents conflicts (e.g., if another library also has a `User` class)

**Why `com.skillbridge`?**
- **Reverse domain notation**: Standard Java convention
- If you owned `skillbridge.com`, you'd use `com.skillbridge`
- Ensures uniqueness (nobody else uses this package name)

---

## 🔍 Part 3: The Main Application Class

### `SkillbridgeBackendApplication.java`

```java
package com.skillbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SkillbridgeBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(SkillbridgeBackendApplication.class, args);
    }
}
```

**What this does:**

1. **`@SpringBootApplication`**:
   - **Magic annotation** that does 3 things:
     - `@Configuration`: Marks this as a configuration class
     - `@EnableAutoConfiguration`: Enables Spring Boot auto-configuration
     - `@ComponentScan`: Scans for Spring components (controllers, services, etc.)
   - **Result**: Spring Boot automatically finds and configures everything

2. **`main()` method**:
   - **Entry point**: Where your application starts
   - **What it does**: Starts the Spring Boot application
   - **Embedded Server**: Spring Boot includes Tomcat, so it starts a web server automatically

**What happens when you run this:**
1. Spring Boot scans all packages under `com.skillbridge`
2. Finds all `@Controller`, `@Service`, `@Repository` classes
3. Configures database connections (from `application.yaml`)
4. Starts embedded Tomcat server on port 8080
5. Your REST APIs are now live!

**Teaching Point:**
- This is **all you need** to start a web server
- No XML configuration, no server setup - Spring Boot does it all!

---

## 🔍 Part 4: Modular Monolith Structure

### What is a Modular Monolith?

**Definition:**
- **Monolith**: One application (one JAR file)
- **Modular**: Organized into clear modules/packages
- **Result**: Looks like microservices from the inside, but deployed as one app

**Our Modules:**
```
com.skillbridge
 ├── auth/          ← Authentication & authorization
 ├── student/       ← Student domain logic
 ├── batch/          ← Batch management
 ├── college/        ← College management
 ├── trainer/        ← Trainer domain
 ├── feedback/       ← Feedback system
 ├── company/        ← Company management
 ├── placement/      ← Placement tracking
 ├── recommendation/ ← Recommendation engine
 ├── reporting/      ← Analytics
 ├── tenant/         ← Multi-tenancy utilities
 └── common/         ← Shared code (used by all modules)
```

**Why this structure?**
- **Clear Boundaries**: Each module has a specific purpose
- **Easy to Find**: Know exactly where code lives
- **Future-Proof**: Can extract modules to microservices later
- **Team Collaboration**: Different developers can work on different modules

---

## 🔍 Part 5: Module Internal Structure

### Standard Module Pattern

Every domain module (student, batch, etc.) follows the same structure:

```
student/
 ├── controller/    ← REST API endpoints
 ├── service/        ← Business logic
 ├── repository/     ← Database access
 ├── entity/         ← Database entities (JPA)
 ├── dto/            ← Data Transfer Objects
 └── mapper/         ← Entity ↔ DTO conversion
```

**Why this layered architecture?**

### Layer 1: Controller (REST API)

**What it does:**
- **Receives HTTP requests** from frontend
- **Validates input** (using `@Valid`)
- **Calls service layer** for business logic
- **Returns HTTP responses** (JSON)

**Example (what we'll create):**
```java
@RestController
@RequestMapping("/api/v1/students")
public class StudentController {
    
    @Autowired
    private StudentService studentService;
    
    @GetMapping("/{id}")
    public StudentDTO getStudent(@PathVariable Long id) {
        return studentService.getStudentById(id);
    }
    
    @PostMapping
    public StudentDTO createStudent(@RequestBody @Valid StudentDTO dto) {
        return studentService.createStudent(dto);
    }
}
```

**Key Annotations:**
- `@RestController`: Marks this as a REST controller
- `@RequestMapping`: Base URL path
- `@GetMapping`, `@PostMapping`: HTTP methods
- `@PathVariable`: URL parameter (e.g., `/students/123`)
- `@RequestBody`: JSON in request body

**Teaching Point:**
- Controllers are **thin** - they just handle HTTP, not business logic
- All business logic goes in the **service layer**

---

### Layer 2: Service (Business Logic)

**What it does:**
- **Contains business rules** and logic
- **Orchestrates** multiple repository calls
- **Validates** business rules (not just input validation)
- **Handles transactions** (if needed)

**Example (what we'll create):**
```java
@Service
public class StudentService {
    
    @Autowired
    private StudentRepository studentRepository;
    
    public StudentDTO createStudent(StudentDTO dto) {
        // Business logic: Check if email already exists
        if (studentRepository.existsByEmail(dto.getEmail())) {
            throw new EmailAlreadyExistsException();
        }
        
        // Convert DTO to Entity
        Student student = new Student();
        student.setName(dto.getName());
        student.setEmail(dto.getEmail());
        student.setCollegeId(TenantContext.getCurrentCollegeId()); // Multi-tenancy
        
        // Save to database
        student = studentRepository.save(student);
        
        // Convert Entity back to DTO
        return StudentMapper.toDTO(student);
    }
}
```

**Key Annotations:**
- `@Service`: Marks this as a service (Spring manages it)
- `@Autowired`: Spring injects dependencies automatically

**Teaching Point:**
- Services contain **business logic**, not HTTP or database details
- This is where you write the "rules" of your application

---

### Layer 3: Repository (Database Access)

**What it does:**
- **Interfaces** that Spring Data JPA implements automatically
- **Database queries** (Spring generates SQL for you)
- **CRUD operations** (Create, Read, Update, Delete)

**Example (what we'll create):**
```java
@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    
    // Spring Data JPA automatically implements this!
    List<Student> findByCollegeId(Long collegeId);
    
    // Custom query
    @Query("SELECT s FROM Student s WHERE s.email = :email")
    Optional<Student> findByEmail(@Param("email") String email);
    
    boolean existsByEmail(String email);
}
```

**Key Points:**
- **Interface, not class**: Spring Data JPA creates implementation automatically
- **Method naming**: `findByEmail` → Spring generates `SELECT * FROM students WHERE email = ?`
- **No SQL needed**: JPA handles it (though you can write custom SQL)

**Teaching Point:**
- Repositories are **data access only** - no business logic
- Spring Data JPA saves you from writing boilerplate code

---

### Layer 4: Entity (Database Tables)

**What it is:**
- **Java class** that represents a database table
- **JPA annotations** map Java fields to database columns

**Example (what we'll create):**
```java
@Entity
@Table(name = "students")
public class Student {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "college_id", nullable = false)
    private Long collegeId;
    
    @Column(nullable = false)
    private String name;
    
    @Column(unique = true, nullable = false)
    private String email;
    
    // Getters, setters (or use Lombok @Data)
}
```

**Key Annotations:**
- `@Entity`: Marks this as a JPA entity
- `@Table`: Database table name
- `@Id`: Primary key
- `@GeneratedValue`: Auto-increment ID
- `@Column`: Maps to database column

**Teaching Point:**
- Entities are **database representations** of your domain objects
- JPA automatically converts between Java objects and database rows

---

### Layer 5: DTO (Data Transfer Object)

**What it is:**
- **Plain Java class** for transferring data between layers
- **Separate from Entity** (why? see below)

**Example (what we'll create):**
```java
public class StudentDTO {
    private Long id;
    private String name;
    private String email;
    // No collegeId (hidden from client)
    // No password (never expose!)
    
    // Getters, setters
}
```

**Why DTOs? (Important!)**

1. **Security**: Don't expose sensitive fields (passwords, internal IDs)
2. **API Contract**: Frontend gets exactly what it needs, nothing more
3. **Flexibility**: Can combine data from multiple entities
4. **Versioning**: Can change DTOs without changing database schema

**Example:**
```java
// Entity (internal)
class User {
    Long id;
    String email;
    String passwordHash;  // ← Never expose!
    Long collegeId;       // ← Internal, not needed by frontend
}

// DTO (what frontend sees)
class UserDTO {
    Long id;
    String email;
    // No password, no collegeId
}
```

**Teaching Point:**
- **Never return entities directly** from controllers
- Always use DTOs to control what clients see

---

### Layer 6: Mapper (Entity ↔ DTO Conversion)

**What it does:**
- **Converts** between Entity and DTO
- **Two directions**: Entity → DTO (for responses), DTO → Entity (for creating)

**Example (what we'll create):**
```java
@Component
public class StudentMapper {
    
    public static StudentDTO toDTO(Student entity) {
        StudentDTO dto = new StudentDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setEmail(entity.getEmail());
        // Don't copy collegeId (internal)
        return dto;
    }
    
    public static Student toEntity(StudentDTO dto) {
        Student entity = new Student();
        entity.setName(dto.getName());
        entity.setEmail(dto.getEmail());
        // Set collegeId from security context (not from DTO!)
        return entity;
    }
}
```

**Why mappers?**
- **Separation**: Keeps conversion logic in one place
- **Reusability**: Use same mapper in multiple services
- **Maintainability**: Change mapping logic in one place

**Teaching Point:**
- Mappers ensure **clean separation** between internal (Entity) and external (DTO) representations

---

## 🔍 Part 6: Common Module

### What is the Common Module?

**Purpose:**
- **Shared code** used by ALL modules
- **Foundation** that everything else builds on

**Structure:**
```
common/
 ├── entity/         ← BaseEntity (id, createdAt, updatedAt)
 ├── exception/      ← Custom exceptions (NotFoundException, etc.)
 ├── config/         ← Configuration classes
 ├── security/       ← Security utilities
 ├── response/       ← Standard API response wrapper
 └── util/           ← Utility classes
```

**Why separate?**
- **DRY Principle**: Don't Repeat Yourself
- **Consistency**: All modules use same base classes
- **Maintainability**: Fix bugs in one place

**Example - BaseEntity:**
```java
// All entities will extend this
@MappedSuperclass
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @CreatedDate
    private LocalDateTime createdAt;
    
    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

**Teaching Point:**
- Common module = **foundation** that prevents code duplication

---

## 🔍 Part 7: Resources Directory

### `src/main/resources/` - Configuration Files

**What goes here:**
- **Configuration files** (YAML, properties)
- **SQL migrations** (Flyway)
- **Static files** (if needed)
- **Templates** (if using server-side rendering)

### `application.yaml` - Application Configuration

**What it is:**
- **Main configuration file** for Spring Boot
- **Currently minimal**: Just application name

**What we'll add:**
```yaml
spring:
  application:
    name: skillbridge-backend
  
  # Database configuration (we'll add this)
  datasource:
    url: jdbc:postgresql://...
    username: ...
    password: ...
  
  # JPA configuration
  jpa:
    hibernate:
      ddl-auto: none  # Use Flyway instead
    show-sql: true    # Log SQL queries (dev only)

# JWT configuration (we'll add this)
jwt:
  secret: your-secret-key
  access-token-expiration-ms: 900000  # 15 minutes
```

**Teaching Point:**
- Configuration in YAML is **externalized** - change without recompiling
- Different configs for dev/staging/production

---

### `db/migration/` - Database Migrations

**What it is:**
- **Flyway** looks here for SQL migration files
- **Versioned**: Files named `V1__description.sql`, `V2__description.sql`

**Example (what we'll create):**
```
db/migration/
 ├── V1__init_core_tables.sql      ← Creates colleges, users, roles
 ├── V2__add_students_table.sql    ← Creates students table
 └── V3__add_batches_table.sql     ← Creates batches table
```

**Why migrations?**
- **Version Control**: Database schema in Git
- **Reproducible**: Same schema on all environments
- **Rollback**: Can undo changes if needed

**Teaching Point:**
- Migrations = **database as code** - track changes like code changes

---

## 🔍 Part 8: Test Directory

### `src/test/java/` - Test Code

**What it is:**
- **Unit tests** and **integration tests**
- **Mirrors main structure**: Same package structure

**Example:**
```java
@SpringBootTest
class StudentServiceTest {
    
    @Autowired
    private StudentService studentService;
    
    @Test
    void testCreateStudent() {
        // Test code here
    }
}
```

**Why tests?**
- **Confidence**: Know your code works
- **Documentation**: Tests show how code should be used
- **Refactoring**: Change code safely knowing tests catch bugs

---

## 🔄 How It All Works Together

### Request Flow Example

**Scenario**: Frontend calls `GET /api/v1/students/123`

```
1. HTTP Request arrives
   ↓
2. Spring Security checks authentication (JWT token)
   ↓
3. StudentController receives request
   @GetMapping("/{id}")
   ↓
4. Controller calls StudentService
   studentService.getStudentById(id)
   ↓
5. Service calls StudentRepository
   studentRepository.findById(id)
   ↓
6. Repository (JPA) executes SQL
   SELECT * FROM students WHERE id = 123
   ↓
7. JPA converts database row → Student Entity
   ↓
8. Repository returns Entity to Service
   ↓
9. Service converts Entity → DTO (using Mapper)
   ↓
10. Service returns DTO to Controller
    ↓
11. Controller returns DTO as JSON
    ↓
12. HTTP Response sent to frontend
```

**Teaching Point:**
- Each layer has a **single responsibility**
- Clear flow makes code **easy to understand and debug**

---

## 🎯 Key Concepts Summary

### 1. **Layered Architecture**
- **Controller** → HTTP layer
- **Service** → Business logic
- **Repository** → Data access
- **Entity** → Database representation
- **DTO** → API representation

### 2. **Separation of Concerns**
- Each layer does **one thing** well
- Changes in one layer don't affect others (mostly)

### 3. **Spring Boot Magic**
- **Auto-configuration**: Spring sets up everything automatically
- **Dependency Injection**: `@Autowired` wires components together
- **Convention over Configuration**: Follow patterns, less config needed

### 4. **Modular Monolith**
- **One application** (monolith)
- **Clear modules** (modular)
- **Future-proof** (can extract to microservices)

---

## 🚀 What's Next?

Now that you understand the structure, we'll:

1. **Configure database** (`application.yaml`)
2. **Create common module** (BaseEntity, exceptions)
3. **Set up Flyway** (first migration)
4. **Build auth module** (JWT, login, register)
5. **Add multi-tenancy** (tenant context, filters)

Each step will add code to these empty folders we created!

---

## 📚 Learning Checklist

After reading this, you should understand:

- ✅ What `pom.xml` does
- ✅ Why we use packages (`com.skillbridge`)
- ✅ What `@SpringBootApplication` does
- ✅ The layered architecture (Controller → Service → Repository)
- ✅ Why we use DTOs (not entities directly)
- ✅ What the common module is for
- ✅ How requests flow through the application

**Ready to start coding?** Let's proceed with database configuration! 🎉

