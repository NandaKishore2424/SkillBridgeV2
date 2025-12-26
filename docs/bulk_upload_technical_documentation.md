# Bulk Upload Feature - Technical Documentation

## 1. Executive Summary
The **Bulk Upload Feature** enables College Administrators to efficiently onboard large numbers of Students and Trainers into the SkillBridge platform via CSV files. This feature automates account creation, profile generation, and role assignment, significantly reducing manual administrative effort. It includes robust error handling to allow partial successes (valid rows are processed even if others fail) and provides detailed error reports for correction.

## 2. Architecture & Design

The feature follows a **Layered Architecture** with strict separation of concerns:

### 2.1 Component Interaction
1.  **Frontend (React)**:
    *   `StudentBulkUploadPage` / `TrainerBulkUploadPage`: UI for file selection and status tracking.
    *   `bulk-upload.ts` (API Service): Handles multipart file uploads and template downloads.
2.  **Backend (Spring Boot)**:
    *   `BulkUploadController`: REST API endpoints protected by Role-Based Access Control (RBAC).
    *   `BulkUploadService`: Orchestrates the upload logic, transaction management, and user creation.
    *   `CsvParserService`: Handles parsing, validation, and mapping of CSV data to DTOs.
3.  **Database (PostgreSQL)**:
    *   `bulk_uploads`: Tracks the overall status of an upload job.
    *   `bulk_upload_results`: specific execution logs for each row (Success/Failure status, error messages).
    *   `users`, `students`, `trainers`: Core entity tables.

### 2.2 Data Flow
`User` -> `Frontend` -> `API` -> `Controller` -> `Service` -> `Database`

## 3. Detailed Implementation

### 3.1 Backend Implementation

#### **Entities & DTOs**
*   **Encapsulation**: Used DTOs (`StudentUploadDTO`, `TrainerUploadDTO`) with OpenCSV annotations (`@CsvBindByName`) to decouple internal entities from the external file format.
*   **Validation**: JSR-303/JSR-380 annotations (`@NotBlank`, `@Email`) ensure data integrity before business logic execution.

#### **Service Layer Strategy**
*   **Fault Tolerance**: The `BulkUploadService` implements a **Partial Success Strategy**.
    *   The main method is annotated with `@Transactional`.
    *   It iterates through parsed data rows.
    *   **Try-Catch Isolation**: Each row processing is wrapped in a `try-catch` block. If a row fails (e.g., duplicate email), the exception is caught, logged to the `bulk_upload_results` table, and the loop continues.
    *   This ensures that a single bad record does not block the valid records from being imported.
*   **Transaction Management**: Successful row operations (saving User and Student/Trainer entities) are persisted within the transaction context. Since exceptions are caught and not re-thrown, the transaction is committed for all successful operations at the end.

#### **Security & Auth**
*   **Role-Based Access Control (RBAC)**: All upload endpoints are restricted to `COLLEGE_ADMIN` via `@PreAuthorize`.
*   **First-Time Login Flow**:
    *   Created accounts are set to `PENDING_SETUP` status.
    *   A `mustChangePassword` flag is set to `true`.
    *   Implemented `/auth/first-login` to force users to change their temporary password (currently their email) upon first access.

### 3.2 Frontend Implementation
*   **TanStack Query (React Query)**: Used for efficient state management of upload history and asynchronous mutations.
*   **Components**: Created dedicated pages for handling uploads, providing immediate visual feedback (Success/Failure counts) and downloadable error details.

## 4. Engineering Principles Followed

1.  **Robustness (Graceful Degradation)**:
    *   *Principle*: The system should function partially in the presence of errors.
    *   *Application*: Implemented row-level exception handling. A 1000-row file with 1 error results in 999 successes, not 0.

2.  **Validation at the Edge**:
    *   *Principle*: Fail fast.
    *   *Application*: CSV headers and basic format are validated immediately by `CsvParserService` before attempting database operations.

3.  **Auditability**:
    *   *Principle*: Key actions must be traceable.
    *   *Application*: Every upload attempt creates a record in `bulk_uploads`, and every row's outcome is logged in `bulk_upload_results`. This provides a complete audit trail.

4.  **Security by Design**:
    *   *Principle*: Least Privilege.
    *   *Application*: Only College Admins can upload. Users are created with minimal initial permissions and forced to cycle credentials immediately.

## 5. Key Workflows Created

### 5.1 Bulk Import Workflow
1.  **Template Download**: Admin downloads a pre-formatted CSV template.
2.  **Upload**: Admin uploads the file.
3.  **Parsring**: Backend validates headers and parses content.
4.  **Processing**:
    *   Check for duplicates (Email/Roll Number).
    *   Create `User` (Pending Setup).
    *   Create `Student`/`Trainer` profile.
    *   Send Welcome Email (simulated).
5.  **Reporting**: Frontend displays a summary: "Processed 50 rows. 48 Success, 2 Failed".

### 5.2 First Login Workflow
1.  User receives welcome email with temporary credential (email).
2.  User logs in.
3.  Backend detects `mustChangePassword=true`.
4.  Frontend redirects to "Setup Password" screen.
5.  User sets new password -> Backend updates status to `ACTIVE`.

## 6. Future Improvements
*   **Asynchronous Processing**: For very large files (e.g., >10k rows), move processing to a background job queue (e.g., RabbitMQ) to avoid request timeouts.
*   **Real Email Integration**: Replace the `EmailService` placeholder with a provider like AWS SES or SendGrid.
*   **Detailed Analytics**: Dashboard widgets showing upload trends over time.
