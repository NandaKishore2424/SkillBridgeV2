# Multi-Tenancy Strategy

This document explains how SkillBridge implements multi-tenancy to isolate data between different colleges (tenants).

---

## What is Multi-Tenancy?

**Multi-tenancy** is an architecture where a single application instance serves multiple customers (tenants), with each tenant's data isolated from others.

**In SkillBridge:**
- Each **college** is a **tenant**
- Students, trainers, batches belong to a specific college
- College A cannot see College B's data
- System Admin can see all colleges' data

---

## Why Multi-Tenancy?

### Without Multi-Tenancy (Separate Instances)
```
College A → Separate Database → Separate Application
College B → Separate Database → Separate Application
College C → Separate Database → Separate Application
```

**Problems:**
- High infrastructure costs (3 databases, 3 applications)
- Difficult to maintain (updates to 3 places)
- No cross-tenant analytics
- Wasted resources (each college uses 10% of server)

### With Multi-Tenancy (Shared Instance)
```
College A ──┐
College B ──┼─→ Single Database → Single Application
College C ──┘
```

**Benefits:**
- Lower costs (shared infrastructure)
- Easier maintenance (one codebase)
- Cross-tenant analytics possible
- Efficient resource usage

---

## Multi-Tenancy Strategy: Tenant ID Column

### Approach: Single Database + `college_id` Column

**Decision:** ✅ Use `college_id` as tenant identifier

### How It Works

**1. Every Tenant-Scoped Table Has `college_id`:**
```sql
CREATE TABLE students (
    id BIGSERIAL PRIMARY KEY,
    college_id BIGINT NOT NULL,  -- Tenant identifier
    name VARCHAR(255),
    email VARCHAR(255),
    ...
    FOREIGN KEY (college_id) REFERENCES colleges(id)
);

CREATE TABLE batches (
    id BIGSERIAL PRIMARY KEY,
    college_id BIGINT NOT NULL,  -- Tenant identifier
    name VARCHAR(255),
    status VARCHAR(50),
    ...
);
```

**2. Platform-Level Tables Do NOT Have `college_id`:**
```sql
CREATE TABLE colleges (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    domain VARCHAR(255),
    ...
    -- No college_id (this IS the tenant)
);

CREATE TABLE system_admins (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255),
    ...
    -- No college_id (platform-level)
);
```

### Data Isolation Example

**College 1 Data:**
```
students: (id=1, college_id=1, name="Alice")
students: (id=2, college_id=1, name="Bob")
batches:  (id=1, college_id=1, name="Java Bootcamp")
```

**College 2 Data:**
```
students: (id=3, college_id=2, name="Charlie")
students: (id=4, college_id=2, name="Diana")
batches:  (id=2, college_id=2, name="Python Bootcamp")
```

**When College 1 admin queries students:**
```sql
SELECT * FROM students WHERE college_id = 1;
-- Returns: Alice, Bob (NOT Charlie, Diana)
```

---

## Implementation: Application-Level Filtering

### Why Application-Level (Not Database RLS)?

**Decision:** ✅ Enforce tenancy at application level

**Reasons:**
1. **Easier Debugging**: Can see all queries in application logs
2. **Flexibility**: Change rules without database migrations
3. **Developer Experience**: Java developers understand Java, not PostgreSQL RLS
4. **Spring Integration**: Works naturally with Spring Security
5. **Testing**: Easier to test application code than database policies

### How It Works

**Step 1: Extract `college_id` from JWT Token**

When user logs in, JWT contains:
```json
{
  "sub": "user123",
  "role": "COLLEGE_ADMIN",
  "collegeId": 1  // ← Tenant identifier
}
```

**Step 2: Store in Security Context**

Spring Security stores authentication in `SecurityContext`:
```java
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
Long collegeId = ((UserPrincipal) authentication.getPrincipal()).getCollegeId();
```

**Step 3: Automatically Filter Queries**

Use Hibernate `@Filter` to automatically add `WHERE college_id = ?`:

```java
@Entity
@Table(name = "students")
@FilterDef(name = "collegeFilter", parameters = @ParamDef(name = "collegeId", type = "long"))
@Filter(name = "collegeFilter", condition = "college_id = :collegeId")
public class Student {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "college_id", nullable = false)
    private Long collegeId;
    
    private String name;
    // ...
}
```

**Step 4: Enable Filter in Repository**

```java
@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    
    @Override
    @EntityGraph(attributePaths = {"batchEnrollments"})
    default List<Student> findAll() {
        // Enable college filter before query
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("collegeFilter")
               .setParameter("collegeId", getCurrentCollegeId());
        return findAll();
    }
    
    private Long getCurrentCollegeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ((UserPrincipal) auth.getPrincipal()).getCollegeId();
    }
}
```

**Step 5: Base Repository Pattern (Better Approach)**

Create a base repository that automatically enables the filter:

```java
@NoRepositoryBean
public interface TenantAwareRepository<T, ID> extends JpaRepository<T, ID> {
    
    @Override
    default List<T> findAll() {
        enableTenantFilter();
        return JpaRepository.super.findAll();
    }
    
    default void enableTenantFilter() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && !isSystemAdmin(auth)) {
            Long collegeId = getCollegeId(auth);
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("collegeFilter")
                   .setParameter("collegeId", collegeId);
        }
    }
    
    private boolean isSystemAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                   .anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
    }
}
```

**Usage:**
```java
public interface StudentRepository extends TenantAwareRepository<Student, Long> {
    // All queries automatically filtered by college_id
    // Unless user is SYSTEM_ADMIN
}
```

---

## System Admin: Cross-Tenant Access

### Requirement

System Admin needs to:
- View all colleges
- See platform-wide metrics
- Access any college's data

### Implementation

**1. Check Role Before Filtering:**

```java
private void enableTenantFilter() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    
    // SYSTEM_ADMIN bypasses tenant filter
    if (isSystemAdmin(auth)) {
        return; // No filter applied
    }
    
    // All other roles are tenant-scoped
    Long collegeId = getCollegeId(auth);
    Session session = entityManager.unwrap(Session.class);
    session.enableFilter("collegeFilter")
           .setParameter("collegeId", collegeId);
}
```

**2. Explicit Cross-Tenant Queries:**

```java
@Service
public class SystemAdminService {
    
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public List<College> getAllColleges() {
        // No tenant filter - returns all colleges
        return collegeRepository.findAll();
    }
    
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public PlatformMetrics getPlatformMetrics() {
        // Aggregates across all colleges
        return PlatformMetrics.builder()
            .totalStudents(studentRepository.count())
            .totalBatches(batchRepository.count())
            .totalColleges(collegeRepository.count())
            .build();
    }
}
```

---

## Tenant Context Utility

### Creating a Helper Class

```java
@Component
public class TenantContext {
    
    public static Long getCurrentCollegeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new UnauthenticatedException("User not authenticated");
        }
        
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        return principal.getCollegeId();
    }
    
    public static boolean isSystemAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && 
               auth.getAuthorities().stream()
                   .anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
    }
    
    public static void requireCollegeId(Long collegeId) {
        if (!isSystemAdmin() && !getCurrentCollegeId().equals(collegeId)) {
            throw new ForbiddenException("Access denied to this college");
        }
    }
}
```

**Usage:**
```java
@Service
public class StudentService {
    
    public Student createStudent(StudentDTO dto) {
        Student student = new Student();
        student.setCollegeId(TenantContext.getCurrentCollegeId()); // Auto-set tenant
        student.setName(dto.getName());
        return studentRepository.save(student);
    }
    
    public Student getStudent(Long studentId) {
        Student student = studentRepository.findById(studentId)
            .orElseThrow(() -> new StudentNotFoundException(studentId));
        
        // Verify tenant access
        TenantContext.requireCollegeId(student.getCollegeId());
        
        return student;
    }
}
```

---

## Security Considerations

### Preventing Tenant Data Leakage

**1. Always Set `college_id` on Create:**
```java
// ❌ BAD - college_id could be null or wrong
student.setCollegeId(dto.getCollegeId());

// ✅ GOOD - always from security context
student.setCollegeId(TenantContext.getCurrentCollegeId());
```

**2. Verify Tenant on Update/Delete:**
```java
public void updateStudent(Long studentId, StudentDTO dto) {
    Student student = studentRepository.findById(studentId)
        .orElseThrow(() -> new StudentNotFoundException(studentId));
    
    // Verify student belongs to current tenant
    TenantContext.requireCollegeId(student.getCollegeId());
    
    // Update student
    student.setName(dto.getName());
    studentRepository.save(student);
}
```

**3. Never Trust Client-Supplied `college_id`:**
```java
// ❌ BAD - client could send wrong college_id
@PostMapping("/students")
public Student createStudent(@RequestBody StudentDTO dto) {
    Student student = new Student();
    student.setCollegeId(dto.getCollegeId()); // DANGEROUS!
    return studentRepository.save(student);
}

// ✅ GOOD - always from security context
@PostMapping("/students")
public Student createStudent(@RequestBody StudentDTO dto) {
    Student student = new Student();
    student.setCollegeId(TenantContext.getCurrentCollegeId()); // Safe
    return studentRepository.save(student);
}
```

**4. Filter All Queries:**
```java
// ❌ BAD - returns all students from all colleges
public List<Student> getAllStudents() {
    return studentRepository.findAll(); // No filter!
}

// ✅ GOOD - automatically filtered
public List<Student> getAllStudents() {
    return studentRepository.findAll(); // Filter enabled in base repository
}
```

---

## Testing Multi-Tenancy

### Unit Tests

```java
@Test
void testStudentIsolation() {
    // Create student for college 1
    Student student1 = createStudentForCollege(1L, "Alice");
    
    // Create student for college 2
    Student student2 = createStudentForCollege(2L, "Bob");
    
    // Login as college 1 admin
    authenticateAsCollegeAdmin(1L);
    
    // Query students
    List<Student> students = studentService.getAllStudents();
    
    // Should only see college 1 students
    assertThat(students).hasSize(1);
    assertThat(students.get(0).getName()).isEqualTo("Alice");
    assertThat(students).doesNotContain(student2);
}
```

### Integration Tests

```java
@SpringBootTest
@AutoConfigureMockMvc
class MultiTenancyIntegrationTest {
    
    @Test
    void testCrossTenantAccessDenied() throws Exception {
        // Create student in college 1
        Student student = createStudent(1L, "Alice");
        
        // Try to access as college 2 admin
        mockMvc.perform(get("/api/v1/students/" + student.getId())
                .header("Authorization", "Bearer " + getTokenForCollege(2L)))
                .andExpect(status().isForbidden());
    }
}
```

---

## Migration Path: Future Options

### Current: Single Database + `college_id`

**When to Keep:**
- < 100 colleges
- Similar data volumes per college
- Need cross-tenant analytics

### Future Option 1: Schema-Per-Tenant

```
Database: skillbridge
  ├── Schema: college_1 (students, batches, ...)
  ├── Schema: college_2 (students, batches, ...)
  └── Schema: college_3 (students, batches, ...)
```

**When to Use:**
- Premium clients want complete isolation
- Regulatory requirements (GDPR, HIPAA)
- Very large tenants (> 1M records)

### Future Option 2: Database-Per-Tenant

```
Database: college_1_db
Database: college_2_db
Database: college_3_db
```

**When to Use:**
- Enterprise clients
- Different database versions per tenant
- Complete infrastructure isolation

---

## Summary

**Our Multi-Tenancy Strategy:**
1. ✅ Single database with `college_id` column
2. ✅ Application-level filtering (not database RLS)
3. ✅ Automatic filtering via Hibernate `@Filter`
4. ✅ System Admin bypasses filters for cross-tenant access
5. ✅ Security: Never trust client-supplied `college_id`
6. ✅ Testing: Verify tenant isolation in tests

**Key Principles:**
- **Isolation**: Each college's data is completely isolated
- **Security**: Tenant context comes from JWT, never from client
- **Flexibility**: Can evolve to schema-per-tenant or database-per-tenant later
- **Simplicity**: Application-level filtering is easier to understand and maintain

