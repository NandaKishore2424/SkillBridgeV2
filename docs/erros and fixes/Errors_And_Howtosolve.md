# Errors and How to Solve Them

**SkillBridge Project - Error Reference Guide**

This document contains all errors encountered during development with their solutions. Use this as a quick reference when facing similar issues.

---

## Table of Contents

1. [Database Migration Errors](#database-migration-errors)
2. [Hibernate/JPA Errors](#hibernatejpa-errors)
3. [Backend Compilation Errors](#backend-compilation-errors)
4. [Frontend Errors](#frontend-errors)

---

## Database Migration Errors

### Error 1: Flyway Migration Failed - Table Already Exists

**Error Message:**
```
ERROR: relation "students" already exists
Script V2__create_student_tables.sql failed
```

**Root Cause:**
Migration script uses `CREATE TABLE` without checking if table already exists. If migration fails partway through or is run multiple times, it will error.

**Solution:**
Use `CREATE TABLE IF NOT EXISTS` for all table creation:
```sql
CREATE TABLE IF NOT EXISTS students (
    -- columns
);
```

Also use:
- `CREATE INDEX IF NOT EXISTS` for indexes
- `INSERT ... ON CONFLICT DO NOTHING` for pre-populated data

**Prevention:**
Always use idempotent SQL statements in migrations.

**Files Affected:**
- `V2__create_student_tables.sql`
- `V3__create_trainer_tables.sql`

---

## Backend Compilation Errors

### Error 1: Cannot find symbol - getFirstName()/getLastName() methods

**Error Message:**
```
cannot find symbol
symbol:   method getFirstName()
location: class com.skillbridge.auth.entity.User
```

**Root Cause:**
The `User` entity doesn't have `firstName` and `lastName` fields. Names are stored in the profile entities (`Student`, `Trainer`) as `fullName`.

**Solution:**
Use the `fullName` field from the profile entities instead of concatenating `firstName + lastName` from User:

```java
// Wrong:
.studentName(feedback.getStudent().getUser().getFirstName() + " " + feedback.getStudent().getUser().getLastName())

// Correct:
.studentName(feedback.getStudent().getFullName())
.trainerName(feedback.getTrainer().getFullName())
```

**Files Affected:**
- `FeedbackServiceImpl.java` - mapToDTO method

**Prevention:**
Always check entity structure before accessing properties. Use profile entities for name/contact information.

---

## Hibernate/JPA Errors

### Error 2: Hibernate Proxy Serialization Error

**Error Message:**
```json
{
  "error": "Type definition error: [simple type, class org.hibernate.proxy.pojo.bytebuddy.ByteBuddyInterceptor]"
}
```

**Root Cause:**
Controllers returning JPA entities directly to API responses. When Jackson tries to serialize entities with lazy-loaded relationships (e.g., `@ManyToOne(fetch = FetchType.LAZY)`), it encounters Hibernate proxy objects that cannot be serialized.

**Solution:**
**NEVER return entities directly from controllers.** Always use DTOs:

1. Create DTO classes:
```java
@Data
@Builder
public class BatchDTO {
    private Long id;
    private Long collegeId;
    private String collegeName;
    private String name;
    // ... other fields
}
```

2. Create converter method in controller:
```java
private BatchDTO convertToDTO(Batch batch) {
    return BatchDTO.builder()
        .id(batch.getId())
        .collegeId(batch.getCollege().getId())
        .collegeName(batch.getCollege().getName())
        .name(batch.getName())
        .build();
}
```

3. Return DTOs from endpoints:
```java
@GetMapping
public ResponseEntity<List<BatchDTO>> getAllBatches() {
    List<Batch> batches = batchRepository.findAll();
    List<BatchDTO> dtos = batches.stream()
        .map(this::convertToDTO)
        .collect(Collectors.toList());
    return ResponseEntity.ok(dtos);
}
```

**Prevention:**
- Always create DTOs for API responses
- Never expose JPA entities directly
- Use `@JsonIgnoreProperties` only as a last resort

**Files Affected:**
- `BatchController.java`
- `CompanyController.java`
- Created: `BatchDTO.java`, `CompanyDTO.java`

---

### Error 3: LazyInitializationException

**Error Message:**
```
org.hibernate.LazyInitializationException: could not initialize proxy - no Session
```

**Root Cause:**
Trying to access lazy-loaded relationships outside of a transaction/session context.

**Solution:**
1. Use DTOs and fetch data within the service/repository layer
2. Use `@Transactional` on service methods
3. Use `fetch = FetchType.EAGER` only when absolutely necessary
4. Use JOIN FETCH in JPQL queries when needed

**Prevention:**
- Always map entities to DTOs within transactional boundaries
- Don't pass entities to the presentation layer

---

## Backend Compilation Errors

### Error 4: Method Not Found in Repository

**Error Message:**
```
cannot find symbol
  symbol:   method countByCollegeId(java.lang.Long)
  location: variable studentRepository of type StudentRepository
```

**Root Cause:**
Using a Spring Data JPA method that hasn't been declared in the repository interface.

**Solution:**
Add the method signature to the repository interface. Spring Data JPA will auto-implement it:

```java
@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    long countByCollegeId(Long collegeId);
    List<Student> findByCollegeId(Long collegeId);
}
```

**Common Spring Data JPA Method Patterns:**
- `countBy...` - Returns count
- `findBy...` - Returns list or single entity
- `existsBy...` - Returns boolean
- `deleteBy...` - Deletes matching entities

**Prevention:**
Declare all repository methods before using them in services/controllers.

**Files Affected:**
- `StudentRepository.java`
- `TrainerRepository.java`
- `BatchRepository.java`
- `CompanyRepository.java`

---

### Error 5: Missing Endpoint - 404 or 500 Error

**Error Message:**
```
GET http://localhost:8080/api/v1/admin/dashboard/stats 500 (Internal Server Error)
```

**Root Cause:**
No controller method mapped to the requested endpoint.

**Solution:**
Create the controller and endpoint:

```java
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@CrossOrigin(origins = {"http://localhost:5173"})
public class DashboardController {
    
    @GetMapping("/stats")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<DashboardStats> getDashboardStats() {
        // Implementation
    }
}
```

**Prevention:**
- Check frontend API calls match backend endpoints
- Use consistent naming conventions
- Document all API endpoints

**Files Affected:**
- Created: `DashboardController.java`

---

## Frontend Errors

### Error 6: Nested Anchor Tags Warning

**Error Message:**
```
<a> cannot contain a nested <a>
This will cause a hydration error.
```

**Root Cause:**
Using React Router's `Link` component as a wrapper around a Button with `asChild` prop, creating nested `<a>` tags.

**Example of Problem:**
```tsx
<Link to="/admin/batches">
  <Button asChild>
    <Link to="/admin/batches">View Batches</Link>
  </Button>
</Link>
```

**Solution:**
Remove one of the Link components:

**Option 1 - Keep outer Link:**
```tsx
<Link to="/admin/batches">
  <Button>View Batches</Button>
</Link>
```

**Option 2 - Keep Button with asChild:**
```tsx
<Button asChild>
  <Link to="/admin/batches">View Batches</Link>
</Button>
```

**Prevention:**
- Don't wrap Link components in other Link components
- Be careful with `asChild` prop - it makes the child the rendered element

**Files Affected:**
- `Dashboard.tsx` - `StatCard` component

---

### Error 7: Module not found - 'sonner'

**Error Message:**
```
Module not found: Can't resolve 'sonner'
```

**Root Cause:**
Importing `toast` from 'sonner' library which is not installed. The project uses a custom toast system based on `@radix-ui/react-toast`.

**Solution:**
Use the existing `useToastNotifications` hook:

```tsx
// Wrong:
import { toast } from 'sonner';

// Correct:
import { useToastNotifications } from '@/shared/hooks/useToastNotifications';

// In component:
const { showSuccess, showError } = useToastNotifications();

// Usage:
showSuccess('Operation successful');
showError('Operation failed');
```

**Prevention:**
- Check existing UI library setup before adding new dependencies
- Use consistent toast system across the application

**Files Affected:**
- `FeedbackManagement.tsx`
- `StudentFeedback.tsx`

---

## Quick Reference

### When You See...

| Error Pattern | Likely Cause | Quick Fix |
|--------------|--------------|-----------|
| `ByteBuddyInterceptor` | Returning entities from API | Create and return DTOs |
| `LazyInitializationException` | Accessing lazy relations outside transaction | Use DTOs, add `@Transactional` |
| `relation already exists` | Migration not idempotent | Use `IF NOT EXISTS` |
| `cannot find symbol: method` | Missing repository method | Add method to repository interface |
| `404` or `500` on API call | Missing endpoint | Create controller method |
| Nested `<a>` warning | Duplicate Link components | Remove one Link or `asChild` |
| `cannot find symbol: getFirstName()` | Wrong entity field access | Use `fullName` from profile entities |
| `Module not found: sonner` | Wrong toast library import | Use `useToastNotifications` hook |

---

## Best Practices to Avoid Errors

### Backend
1. ✅ Always use DTOs for API responses
2. ✅ Use `IF NOT EXISTS` in migrations
3. ✅ Declare repository methods before using them
4. ✅ Add `@Transactional` to service methods that modify data
5. ✅ Use `@CrossOrigin` on controllers for frontend access
6. ✅ Log errors with context information

### Frontend
1. ✅ Match API endpoint URLs exactly
2. ✅ Handle loading and error states
3. ✅ Avoid nested Link components
4. ✅ Use TypeScript interfaces for API responses
5. ✅ Check browser console for errors

---

## Additional Resources

- [Spring Data JPA Query Methods](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/#jpa.query-methods)
- [Flyway Best Practices](https://flywaydb.org/documentation/concepts/migrations)
- [Jackson JSON Serialization](https://github.com/FasterXML/jackson-docs)
- [React Router v6 Documentation](https://reactrouter.com/)

---

**Last Updated:** 2025-12-26
**Project:** SkillBridge v2
