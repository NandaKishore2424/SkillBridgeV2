# Change Log - 2026-04-10

This document records all changes made today, the reasons behind each change, the decisions taken, the concepts used, and where each change was implemented (file + line references).

## 1) Goals for the Day

- Replace the old simple token flow with JWT + refresh rotation and safer storage.
- Enforce tenant isolation centrally instead of relying on every query.
- Add pagination for admin list APIs and UI pages.
- Make bulk uploads async to avoid blocking requests.
- Add rate limiting and standardized error responses.
- Keep existing behavior stable (backward-compatible where possible).

## 2) Decisions and Rationale

- **JWT for access tokens**: prevents tampering and standardizes auth. Implemented in [skillbridge-backend/src/main/java/com/skillbridge/auth/service/JwtService.java](skillbridge-backend/src/main/java/com/skillbridge/auth/service/JwtService.java#L17).
- **Refresh token rotation**: reduces replay risk and allows server-side revocation. Implemented in [skillbridge-backend/src/main/java/com/skillbridge/auth/service/AuthService.java](skillbridge-backend/src/main/java/com/skillbridge/auth/service/AuthService.java#L102).
- **HttpOnly refresh cookie**: protects refresh tokens from XSS. Implemented in [skillbridge-backend/src/main/java/com/skillbridge/auth/controller/AuthController.java](skillbridge-backend/src/main/java/com/skillbridge/auth/controller/AuthController.java#L98).
- **Tenant isolation via Hibernate filter**: reduces the chance of query leaks by enforcing a global filter. Implemented in [skillbridge-backend/src/main/java/com/skillbridge/common/tenant/TenantFilter.java](skillbridge-backend/src/main/java/com/skillbridge/common/tenant/TenantFilter.java#L22) and entity filters like [skillbridge-backend/src/main/java/com/skillbridge/student/entity/Student.java](skillbridge-backend/src/main/java/com/skillbridge/student/entity/Student.java#L20).
- **Paged responses**: prevent large payloads and improve UI performance. Implemented with [skillbridge-backend/src/main/java/com/skillbridge/common/dto/PagedResponse.java](skillbridge-backend/src/main/java/com/skillbridge/common/dto/PagedResponse.java#L10).
- **Async bulk upload**: avoid timeouts and keep UI responsive for large files. Implemented in [skillbridge-backend/src/main/java/com/skillbridge/bulkupload/service/BulkUploadJobService.java](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/service/BulkUploadJobService.java#L35).
- **Rate limiting**: protect the API from abuse/spikes. Implemented in [skillbridge-backend/src/main/java/com/skillbridge/common/throttle/RateLimitingFilter.java](skillbridge-backend/src/main/java/com/skillbridge/common/throttle/RateLimitingFilter.java#L22).
- **Standardized error payloads**: easier frontend error handling and consistent logging. Implemented in [skillbridge-backend/src/main/java/com/skillbridge/common/exception/GlobalExceptionHandler.java](skillbridge-backend/src/main/java/com/skillbridge/common/exception/GlobalExceptionHandler.java#L19).

## 3) Concepts Used

- **Stateless auth** with signed tokens (JWT): [JwtService token creation](skillbridge-backend/src/main/java/com/skillbridge/auth/service/JwtService.java#L30).
- **Refresh token rotation** and server-side revocation: [AuthService refresh flow](skillbridge-backend/src/main/java/com/skillbridge/auth/service/AuthService.java#L102).
- **HttpOnly cookie storage** for refresh tokens: [AuthController cookies](skillbridge-backend/src/main/java/com/skillbridge/auth/controller/AuthController.java#L98).
- **Tenant-aware data isolation** using Hibernate filters: [TenantFilter enablement](skillbridge-backend/src/main/java/com/skillbridge/common/tenant/TenantFilter.java#L41) and [entity filters](skillbridge-backend/src/main/java/com/skillbridge/student/entity/Student.java#L20).
- **Pagination** with Page and PageRequest: [StudentAdminController](skillbridge-backend/src/main/java/com/skillbridge/student/controller/StudentAdminController.java#L28).
- **Async processing** with Spring @Async and custom executor: [AsyncConfig](skillbridge-backend/src/main/java/com/skillbridge/common/config/AsyncConfig.java#L12).
- **Rate limiting** with token/IP keys: [RateLimitingFilter keying](skillbridge-backend/src/main/java/com/skillbridge/common/throttle/RateLimitingFilter.java#L22).
- **Unified error schema** for API responses: [ErrorResponse](skillbridge-backend/src/main/java/com/skillbridge/common/exception/ErrorResponse.java#L11).

## 4) Backend Changes (What + Where + Why)

### 4.1 Authentication and Token Lifecycle

- **JWT access token generation** using HS256 and claims: [JwtService generateAccessToken](skillbridge-backend/src/main/java/com/skillbridge/auth/service/JwtService.java#L30). This replaces the old `token_userId_timestamp` format.
- **JWT validation and user resolution**: [TokenAuthenticationFilter](skillbridge-backend/src/main/java/com/skillbridge/auth/filter/TokenAuthenticationFilter.java#L31). Ensures the user is fetched only after token validation.
- **Refresh token model with hashed storage**: [RefreshToken entity](skillbridge-backend/src/main/java/com/skillbridge/auth/entity/RefreshToken.java#L23). Keeps only a hash in the database for security.
- **Refresh token repository methods for rotation and cleanup**: [RefreshTokenRepository](skillbridge-backend/src/main/java/com/skillbridge/auth/repository/RefreshTokenRepository.java#L12).
- **Login now returns JWT + refresh token**: [AuthService login](skillbridge-backend/src/main/java/com/skillbridge/auth/service/AuthService.java#L49).
- **Refresh endpoint rotates tokens**: [AuthService refreshToken](skillbridge-backend/src/main/java/com/skillbridge/auth/service/AuthService.java#L102).
- **Logout revokes refresh token**: [AuthService logout](skillbridge-backend/src/main/java/com/skillbridge/auth/service/AuthService.java#L219).
- **Refresh token issuance uses secure random + hash**: [AuthService issueRefreshToken](skillbridge-backend/src/main/java/com/skillbridge/auth/service/AuthService.java#L231).
- **Cookie-based refresh support**: [AuthController refresh + logout + cookie helpers](skillbridge-backend/src/main/java/com/skillbridge/auth/controller/AuthController.java#L45).
- **RefreshTokenRequest made optional** to allow cookie-based refresh: [RefreshTokenRequest](skillbridge-backend/src/main/java/com/skillbridge/auth/dto/RefreshTokenRequest.java#L10).
- **AuthResponse cleanup** (comment removed, now real JWT): [AuthResponse](skillbridge-backend/src/main/java/com/skillbridge/auth/dto/AuthResponse.java#L12).

### 4.2 Tenant Enforcement (Global Isolation)

- **Tenant filter added to security chain**: [SecurityConfig filter chain](skillbridge-backend/src/main/java/com/skillbridge/common/config/SecurityConfig.java#L24).
- **Tenant filter logic** (enable Hibernate filter for non-system admins): [TenantFilter](skillbridge-backend/src/main/java/com/skillbridge/common/tenant/TenantFilter.java#L22).
- **Entity-level filters** applied to tenant-scoped tables:
  - [Students](skillbridge-backend/src/main/java/com/skillbridge/student/entity/Student.java#L20)
  - [Trainers](skillbridge-backend/src/main/java/com/skillbridge/trainer/entity/Trainer.java#L18)
  - [Batches](skillbridge-backend/src/main/java/com/skillbridge/batch/entity/Batch.java#L23)
  - [Companies](skillbridge-backend/src/main/java/com/skillbridge/company/entity/Company.java#L18)
  - [College admins](skillbridge-backend/src/main/java/com/skillbridge/college/entity/CollegeAdmin.java#L17)
  - [Bulk uploads](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/entity/BulkUpload.java#L19)

### 4.3 Pagination (Backend)

- **PagedResponse wrapper**: [PagedResponse](skillbridge-backend/src/main/java/com/skillbridge/common/dto/PagedResponse.java#L10).
- **Student admin list** now paged: [StudentAdminController getAllStudents](skillbridge-backend/src/main/java/com/skillbridge/student/controller/StudentAdminController.java#L28).
- **Trainer admin list** now paged: [TrainerAdminController getAllTrainers](skillbridge-backend/src/main/java/com/skillbridge/trainer/controller/TrainerAdminController.java#L30).
- **Batch admin list** now paged: [BatchController getAllBatches](skillbridge-backend/src/main/java/com/skillbridge/batch/controller/BatchController.java#L48).
- **Company admin list** now paged: [CompanyController getAllCompanies](skillbridge-backend/src/main/java/com/skillbridge/company/controller/CompanyController.java#L38).
- **Repository methods with Pageable**:
  - [StudentRepository](skillbridge-backend/src/main/java/com/skillbridge/student/repository/StudentRepository.java#L17)
  - [TrainerRepository](skillbridge-backend/src/main/java/com/skillbridge/trainer/repository/TrainerRepository.java#L17)
  - [BatchRepository](skillbridge-backend/src/main/java/com/skillbridge/batch/repository/BatchRepository.java#L14)
  - [CompanyRepository](skillbridge-backend/src/main/java/com/skillbridge/company/repository/CompanyRepository.java#L12)
- **Service paging helpers**:
  - [StudentService getStudentsByCollege](skillbridge-backend/src/main/java/com/skillbridge/student/service/StudentService.java#L111)
  - [TrainerService getTrainersByCollege](skillbridge-backend/src/main/java/com/skillbridge/trainer/service/TrainerService.java#L102)

### 4.4 Async Bulk Uploads

- **Async executor** for bulk jobs: [AsyncConfig bulkUploadExecutor](skillbridge-backend/src/main/java/com/skillbridge/common/config/AsyncConfig.java#L14).
- **Async processing service**: [BulkUploadJobService](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/service/BulkUploadJobService.java#L35).
- **Async student upload job**: [processStudentUploadAsync](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/service/BulkUploadJobService.java#L50).
- **Async trainer upload job**: [processTrainerUploadAsync](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/service/BulkUploadJobService.java#L106).
- **New async entrypoints** in service: [BulkUploadService startStudentUpload](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/service/BulkUploadService.java#L50) and [startTrainerUpload](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/service/BulkUploadService.java#L78).
- **Controller returns 202 Accepted** and queues job: [BulkUploadController endpoints](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/controller/BulkUploadController.java#L31).
- **Byte array CSV parsing** to avoid re-reading InputStream during async: [CsvParserService byte[] parsing](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/service/CsvParserService.java#L32).
- **Bulk upload history pagination ready** (repository Pageable): [BulkUploadRepository](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/repository/BulkUploadRepository.java#L18).

### 4.5 Rate Limiting

- **Rate limiting filter** with token/IP keying: [RateLimitingFilter](skillbridge-backend/src/main/java/com/skillbridge/common/throttle/RateLimitingFilter.java#L22).
- **Filter wired into security chain** before auth: [SecurityConfig filter ordering](skillbridge-backend/src/main/java/com/skillbridge/common/config/SecurityConfig.java#L48).

### 4.6 Standardized Error Responses

- **Error DTO** used by all handlers: [ErrorResponse](skillbridge-backend/src/main/java/com/skillbridge/common/exception/ErrorResponse.java#L11).
- **Global exception handler** now returns consistent schema: [GlobalExceptionHandler](skillbridge-backend/src/main/java/com/skillbridge/common/exception/GlobalExceptionHandler.java#L19).

### 4.7 Configuration and Dependencies

- **JWT config** (secret + TTLs): [application.yaml jwt](skillbridge-backend/src/main/resources/application.yaml#L14).
- **Rate limit config** (requests per minute): [application.yaml rateLimit](skillbridge-backend/src/main/resources/application.yaml#L19).
- **JWT dependencies**: [pom.xml jjwt](skillbridge-backend/pom.xml#L35).
- **Bucket4j dependency**: [pom.xml bucket4j](skillbridge-backend/pom.xml#L52).

## 5) Frontend Changes (What + Where + Why)

### 5.1 Auth and Token Handling

- **Cookie support in API client**: [api client withCredentials](skillbridge-frontend/src/api/client.ts#L16).
- **Refresh endpoint can be called without refresh token in body**: [auth refreshToken](skillbridge-frontend/src/api/auth.ts#L63).
- **Logout handles cookie-based flow**: [auth logout](skillbridge-frontend/src/api/auth.ts#L76).
- **Auth context prefers cookie refresh** when localStorage has no refresh token: [AuthContext refresh path](skillbridge-frontend/src/shared/contexts/AuthContext.tsx#L170).
- **Refresh token no longer persisted by default**: [AuthContext storage policy](skillbridge-frontend/src/shared/contexts/AuthContext.tsx#L228).

### 5.2 Pagination (UI)

- **Paged API shape** shared with frontend: [PagedResponse in college-admin API](skillbridge-frontend/src/api/college-admin.ts#L11).
- **Paged API calls**:
  - [getBatches](skillbridge-frontend/src/api/college-admin.ts#L55)
  - [getCompanies](skillbridge-frontend/src/api/college-admin.ts#L110)
  - [getTrainers](skillbridge-frontend/src/api/college-admin.ts#L175)
  - [getStudents](skillbridge-frontend/src/api/college-admin.ts#L232)
- **UI pagination controls**:
  - [BatchesList pagination](skillbridge-frontend/src/pages/admin/batches/BatchesList.tsx#L334)
  - [CompaniesList pagination](skillbridge-frontend/src/pages/admin/companies/CompaniesList.tsx#L239)
  - [StudentsList pagination](skillbridge-frontend/src/pages/admin/students/StudentsList.tsx#L260)
  - [TrainersList pagination](skillbridge-frontend/src/pages/admin/trainers/TrainersList.tsx#L283)
- **Page-aware queries** (query keys include page/size):
  - [BatchesList queryKey](skillbridge-frontend/src/pages/admin/batches/BatchesList.tsx#L99)
  - [CompaniesList queryKey](skillbridge-frontend/src/pages/admin/companies/CompaniesList.tsx#L74)
  - [StudentsList queryKey](skillbridge-frontend/src/pages/admin/students/StudentsList.tsx#L73)
  - [TrainersList queryKey](skillbridge-frontend/src/pages/admin/trainers/TrainersList.tsx#L84)

### 5.3 Bulk Upload UI (Async)

- **Polling for async completion**: [Student upload polling](skillbridge-frontend/src/pages/admin/students/StudentBulkUploadPage.tsx#L52) and [Trainer upload polling](skillbridge-frontend/src/pages/admin/trainers/TrainerBulkUploadPage.tsx#L52).
- **Queued status messaging**: [Student upload UI](skillbridge-frontend/src/pages/admin/students/StudentBulkUploadPage.tsx#L197) and [Trainer upload UI](skillbridge-frontend/src/pages/admin/trainers/TrainerBulkUploadPage.tsx#L196).

## 6) Documentation and Workspace Files Added

- **Project deep dive documentation**: [SKILLBRIDGE_CODEBASE_DEEP_DIVE.md](SKILLBRIDGE_CODEBASE_DEEP_DIVE.md#L1).
- **Complete project documentation**: [docs/COMPLETE_PROJECT_DOCUMENTATION.md](docs/COMPLETE_PROJECT_DOCUMENTATION.md#L1).
- **Editor setting for Java build config**: [.vscode/settings.json](.vscode/settings.json#L1).

## 7) Behavior Changes and Compatibility Notes

- Access tokens are now JWTs; any older simple tokens no longer authenticate. Primary logic now starts at [JwtService token validation](skillbridge-backend/src/main/java/com/skillbridge/auth/service/JwtService.java#L46).
- Refresh tokens rotate on each refresh, so clients must store the latest one. Rotation logic starts at [AuthService refreshToken](skillbridge-backend/src/main/java/com/skillbridge/auth/service/AuthService.java#L102).
- Refresh tokens are now stored in HttpOnly cookies by default; localStorage is only used if already present (backward compatibility). See [AuthContext storage policy](skillbridge-frontend/src/shared/contexts/AuthContext.tsx#L228).
- Admin list endpoints now return paged responses instead of raw lists. See [StudentAdminController getAllStudents](skillbridge-backend/src/main/java/com/skillbridge/student/controller/StudentAdminController.java#L28).
- Bulk upload endpoints now return 202 Accepted and process asynchronously. See [BulkUploadController](skillbridge-backend/src/main/java/com/skillbridge/bulkupload/controller/BulkUploadController.java#L31).

## 8) Build Note (Current Blocker)

- Maven Central does not have the bucket4j version originally used. The dependency is currently set in [skillbridge-backend/pom.xml](skillbridge-backend/pom.xml#L52), and the build fails if the artifact is missing. This is a dependency resolution issue, not a code issue.

---

If you want this split into smaller per-feature docs, I can generate separate files (auth, tenancy, pagination, bulk uploads, frontend) and link them from this file.
