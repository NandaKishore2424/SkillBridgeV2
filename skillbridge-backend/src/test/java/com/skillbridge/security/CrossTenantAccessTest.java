package com.skillbridge.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TestAuthentication;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * No college can read or change another college's data through the API.
 *
 * <p><b>How it works.</b> Every endpoint that takes an id in its path and is open
 * to a college-scoped role (COLLEGE_ADMIN, TRAINER or STUDENT) is read from
 * Spring's own route table, not listed by hand. Each is then called, as every
 * one of those roles from college MINE, with college THEIRS's ids. It must
 * answer <b>404</b>: a 403 would confirm the id exists. Before and after every
 * call, THEIRS's rows are fingerprinted, and <b>any change fails the test</b>,
 * whatever the status code said. A leak that returns 404 after writing is still
 * a leak.
 *
 * <p>Endpoints with more than one id are also called with one id from each
 * college: my batch with their student, their batch with my student. And the
 * endpoints that carry ids in the <em>body</em> (assigning trainers or
 * companies, grading students) get explicit cases, because the route table
 * cannot see a body.
 *
 * <p><b>New endpoints cannot skip this.</b> An endpoint with a path id is either
 * exercised here, which needs its id names in {@link #resolve} and its body in
 * {@link #BODIES}, or listed in {@link #EXEMPT} with the reason. Anything else
 * fails {@link #everyTenantEndpointIsCovered}.
 *
 * <p>Written 2026-09-19, after a scratch probe showed a college admin writing 8
 * rows into another college's batch through the progress backfill.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTest
class CrossTenantAccessTest {

    private static final Set<String> TENANT_ROLES = Set.of("COLLEGE_ADMIN", "TRAINER", "STUDENT");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{(\\w+)}");

    /** Endpoints with a path id that deliberately are not tenant-scoped, and why. */
    private static final Map<String, String> EXEMPT = Map.of(
            "PUT /api/v1/students/me/skills/{skillId}",
            "skillId is the global skill catalogue, not tenant data; the change applies to the caller's own profile",
            "DELETE /api/v1/students/me/skills/{skillId}",
            "skillId is the global skill catalogue, not tenant data; the change applies to the caller's own profile");

    /** A valid body for each write endpoint that takes one, so validation cannot answer before the tenant check. */
    private static final Map<String, Function<Tenant, Object>> BODIES = Map.ofEntries(
            Map.entry("PUT /api/v1/admin/batches/{id}", t -> Map.of("name", "Renamed by another college", "status", "ACTIVE")),
            Map.entry("PATCH /api/v1/admin/batches/{id}/status", t -> Map.of("status", "COMPLETED")),
            Map.entry("POST /api/v1/admin/batches/{id}/trainers", t -> Map.of("trainerIds", List.of(t.trainerId()))),
            Map.entry("POST /api/v1/admin/batches/{id}/companies", t -> Map.of("companyIds", List.of(t.companyId()))),
            Map.entry("PUT /api/v1/admin/companies/{id}", t -> Map.of("name", "Renamed Corp", "domain", "renamed.example", "hiringType", "FULL_TIME")),
            Map.entry("PUT /api/v1/trainer/topics/{topicId}/progress", t -> Map.of("studentId", t.studentId(), "status", "COMPLETED", "score", 90)),
            Map.entry("PUT /api/v1/trainer/topics/{topicId}/progress/bulk", t -> Map.of("studentIds", List.of(t.studentId()), "status", "COMPLETED")),
            Map.entry("PUT /api/v1/admin/students/{id}", t -> Map.of("degree", "Changed by another college")),
            Map.entry("PATCH /api/v1/admin/students/{id}/status", t -> Map.of("isActive", false)),
            Map.entry("POST /api/v1/batches/{batchId}/syllabus/modules", t -> Map.of("name", "Injected module", "displayOrder", 99)),
            Map.entry("PUT /api/v1/syllabus/modules/{moduleId}", t -> Map.of("name", "Renamed module")),
            Map.entry("POST /api/v1/syllabus/modules/{moduleId}/submodules", t -> Map.of("name", "Injected submodule", "displayOrder", 99)),
            Map.entry("PUT /api/v1/syllabus/submodules/{submoduleId}", t -> Map.of("name", "Renamed submodule")),
            Map.entry("POST /api/v1/syllabus/submodules/{submoduleId}/topics", t -> Map.of("name", "Injected topic", "displayOrder", 99)),
            Map.entry("PUT /api/v1/syllabus/topics/{topicId}", t -> Map.of("name", "Renamed topic")),
            Map.entry("PATCH /api/v1/admin/trainers/{id}/status", t -> Map.of("isActive", false)),
            Map.entry("PUT /api/v1/admin/trainers/{id}", t -> Map.of("fullName", "Renamed by another college")),
            // Added when `reject` stopped discarding the admin's reason and
            // started taking a body. This test noticed within the same run:
            // an endpoint that grows a body drops out of the replay until its
            // body is written down here.
            Map.entry("POST /api/v1/admin/enrollment-requests/{requestId}/reject",
                    t -> Map.of("reason", "Rejected by another college")));

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private TenantFixture mineFixture;
    private TenantFixture theirsFixture;
    private Tenant mine;
    private Tenant theirs;
    private Map<String, String> tokens;

    /** Every id a request might need, for one college. */
    record Tenant(Long collegeId, Long batchId, Long studentId, Long studentUserId, Long trainerId,
                  Long trainerUserId, Long adminUserId, Long companyId, Long moduleId, Long submoduleId,
                  Long topicId, Long requestId, Long projectId, Long uploadId) {
    }

    /** One endpoint, as the route table describes it. */
    record Endpoint(String method, String pattern, Set<String> roles, boolean hasBody) {
        String key() {
            return method + " " + pattern;
        }
    }

    @BeforeEach
    void seed() {
        mineFixture = new TenantFixture(jdbc, "XTMINE");
        mineFixture.seed(1, 2, 1);
        mine = extras(mineFixture);
        theirsFixture = new TenantFixture(jdbc, "XTTHEIRS");
        theirsFixture.seed(1, 2, 1);
        theirs = extras(theirsFixture);

        tokens = Map.of(
                "COLLEGE_ADMIN", token(mine.adminUserId(), "COLLEGE_ADMIN"),
                "TRAINER", token(mine.trainerUserId(), "TRAINER"),
                "STUDENT", token(mine.studentUserId(), "STUDENT"));
    }

    @AfterEach
    void cleanUp() {
        // Extras of both colleges first: a leak can leave a row of theirs pointing
        // at a user of ours (enrollment_requests.reviewed_by), which would block
        // removing our users.
        List<TenantFixture> fixtures = new ArrayList<>();
        if (mineFixture != null) fixtures.add(mineFixture);
        if (theirsFixture != null) fixtures.add(theirsFixture);
        fixtures.forEach(f -> removeExtras(f.collegeId));
        fixtures.forEach(TenantFixture::remove);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("every tenant endpoint with a path id is exercised or exempted with a reason")
    void everyTenantEndpointIsCovered() {
        List<String> uncovered = new ArrayList<>();
        for (Endpoint endpoint : tenantEndpoints()) {
            if (EXEMPT.containsKey(endpoint.key())) {
                continue;
            }
            for (String variable : variables(endpoint.pattern())) {
                try {
                    resolve(endpoint.pattern(), variable, theirs);
                } catch (IllegalArgumentException unmapped) {
                    uncovered.add(endpoint.key() + "  (no id for {" + variable + "})");
                }
            }
            if (endpoint.hasBody() && !BODIES.containsKey(endpoint.key())) {
                uncovered.add(endpoint.key() + "  (takes a body; add one to BODIES)");
            }
        }
        assertThat(uncovered)
                .as("""
                    These endpoints take an id in the path but this test does not know how to call \
                    them with another college's data. Teach it (resolve / BODIES) or add them to \
                    EXEMPT with the reason they are not tenant data.""")
                .isEmpty();
        assertThat(tenantEndpoints()).as("the route table should hold the tenant endpoints").hasSizeGreaterThan(40);
    }

    @Test
    @DisplayName("another college's ids: 404 from every tenant endpoint, and none of their data changes")
    void anotherCollegesIdsAreInvisible() throws Exception {
        List<String> leaks = new ArrayList<>();
        Set<String> shapeless = new java.util.TreeSet<>();
        int calls = 0;

        for (Endpoint endpoint : tenantEndpoints()) {
            if (EXEMPT.containsKey(endpoint.key())) {
                continue;
            }
            List<String> variables = variables(endpoint.pattern());
            // All ids theirs; then, when there are several, each one theirs alone.
            List<Set<String>> variants = new ArrayList<>();
            variants.add(Set.copyOf(variables));
            if (variables.size() > 1) {
                variables.forEach(v -> variants.add(Set.of(v)));
            }

            for (Set<String> theirsVariables : variants) {
                for (String role : endpoint.roles()) {
                    Map<String, Long> ids = new LinkedHashMap<>();
                    for (String variable : variables) {
                        ids.put(variable, resolve(endpoint.pattern(), variable,
                                theirsVariables.contains(variable) ? theirs : mine));
                    }
                    Tenant bodyTenant = theirsVariables.containsAll(variables) ? theirs : mine;
                    Outcome outcome = call(endpoint, role, ids, bodyTenant);
                    calls++;
                    if (outcome.status() == 404 && !outcome.errorBody()) {
                        shapeless.add("%-6s %s".formatted(endpoint.method(), endpoint.pattern()));
                    }
                    if (outcome.status() != 404 || outcome.mutated()) {
                        leaks.add("%-6s %-62s as %-13s theirs=%s -> %d%s".formatted(
                                endpoint.method(), expand(endpoint.pattern(), ids), role, theirsVariables,
                                outcome.status(), outcome.mutated() ? "  AND CHANGED THEIR DATA" : ""));
                    }
                }
            }
        }

        assertThat(calls).as("a run that calls nothing proves nothing").isGreaterThan(80);
        assertThat(leaks)
                .as("A college-scoped caller reached another college's data. Each line is one call "
                        + "that did not answer 404, or that changed the other college's rows.")
                .isEmpty();
        // One error model: every 404 is an ErrorResponse, not an empty body or a
        // bare string a client has to special-case (added 2026-09-19, Phase 3).
        assertThat(shapeless)
                .as("These answered 404 without the ErrorResponse body (status, error, message, path). "
                        + "Throw ResourceNotFoundException instead of building the response by hand.")
                .isEmpty();
    }

    @Test
    @DisplayName("ids carried in a request body cannot pull in another college's data either")
    void bodyIdsFromAnotherCollegeChangeNothing() throws Exception {
        record BodyCase(String role, HttpMethod method, String uri, Object body) {
        }
        List<BodyCase> cases = List.of(
                new BodyCase("COLLEGE_ADMIN", HttpMethod.POST, "/api/v1/admin/batches/" + mine.batchId() + "/trainers",
                        Map.of("trainerIds", List.of(theirs.trainerId()))),
                new BodyCase("COLLEGE_ADMIN", HttpMethod.POST, "/api/v1/admin/batches/" + mine.batchId() + "/companies",
                        Map.of("companyIds", List.of(theirs.companyId()))),
                new BodyCase("TRAINER", HttpMethod.PUT, "/api/v1/trainer/topics/" + mine.topicId() + "/progress",
                        Map.of("studentId", theirs.studentId(), "status", "COMPLETED", "score", 90)),
                new BodyCase("TRAINER", HttpMethod.PUT, "/api/v1/trainer/topics/" + mine.topicId() + "/progress/bulk",
                        Map.of("studentIds", List.of(theirs.studentId()), "status", "COMPLETED")));

        List<String> leaks = new ArrayList<>();
        for (BodyCase c : cases) {
            String before = fingerprint(theirs.collegeId());
            int status = mvc.perform(request(c.method(), c.uri())
                            .header("Authorization", "Bearer " + tokens.get(c.role()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(c.body())))
                    .andReturn().getResponse().getStatus();
            // Nothing of theirs may change, AND nothing of theirs may be linked to
            // ours: an assignment row lives under OUR batch, so their fingerprint
            // would not show it.
            boolean linked = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM batch_trainers WHERE batch_id = ? AND trainer_id = ?)
                        OR EXISTS (SELECT 1 FROM batch_companies WHERE batch_id = ? AND company_id = ?)
                    """, Boolean.class, mine.batchId(), theirs.trainerId(), mine.batchId(), theirs.companyId()));
            if (!before.equals(fingerprint(theirs.collegeId())) || linked) {
                leaks.add("%s %s as %s -> %d%s".formatted(c.method(), c.uri(), c.role(), status,
                        linked ? "  AND LINKED THEIR ROW TO OUR BATCH" : "  AND CHANGED THEIR DATA"));
            }
        }
        assertThat(leaks).isEmpty();
    }

    // ------------------------------------------------------------------

    record Outcome(int status, boolean mutated, boolean errorBody) {
    }

    /** The ErrorResponse shape GlobalExceptionHandler writes. */
    private boolean isErrorBody(MvcResult result) {
        try {
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            return body != null && body.path("status").asInt() == result.getResponse().getStatus()
                    && body.hasNonNull("error") && body.hasNonNull("message") && body.hasNonNull("path");
        } catch (Exception e) {
            return false;
        }
    }

    private Outcome call(Endpoint endpoint, String role, Map<String, Long> ids, Tenant bodyTenant) throws Exception {
        String before = fingerprint(theirs.collegeId());
        var builder = request(HttpMethod.valueOf(endpoint.method()), expand(endpoint.pattern(), ids))
                .header("Authorization", "Bearer " + tokens.get(role));
        if (endpoint.hasBody()) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(BODIES.get(endpoint.key()).apply(bodyTenant)));
        }
        MvcResult result = mvc.perform(builder).andReturn();
        boolean mutated = !before.equals(fingerprint(theirs.collegeId()));
        if (mutated) {
            // Put their college back, so one leak cannot mask the next as a 404.
            removeExtras(theirsFixture.collegeId);
            theirsFixture.seed(1, 2, 1);
            theirs = extras(theirsFixture);
        }
        return new Outcome(result.getResponse().getStatus(), mutated, isErrorBody(result));
    }

    private List<Endpoint> tenantEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            PreAuthorize rule = handler.getMethodAnnotation(PreAuthorize.class);
            if (rule == null || info.getPathPatternsCondition() == null) {
                continue;
            }
            Set<String> roles = new TreeSet<>();
            TENANT_ROLES.stream().filter(rule.value()::contains).forEach(roles::add);
            if (roles.isEmpty()) {
                continue; // SYSTEM_ADMIN only: unscoped by design
            }
            boolean hasBody = java.util.Arrays.stream(handler.getMethodParameters())
                    .anyMatch(p -> p.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class));
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.contains("{")) {
                    continue;
                }
                for (var method : info.getMethodsCondition().getMethods()) {
                    endpoints.add(new Endpoint(method.name(), pattern, roles, hasBody));
                }
            }
        }
        endpoints.sort(java.util.Comparator.comparing(Endpoint::key));
        return endpoints;
    }

    private static List<String> variables(String pattern) {
        List<String> names = new ArrayList<>();
        Matcher m = PATH_VARIABLE.matcher(pattern);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static String expand(String pattern, Map<String, Long> ids) {
        String uri = pattern;
        for (Map.Entry<String, Long> id : ids.entrySet()) {
            uri = uri.replace("{" + id.getKey() + "}", String.valueOf(id.getValue()));
        }
        return uri;
    }

    /** Which of a college's ids a path variable means, by its name and the path it sits in. */
    private static Long resolve(String pattern, String variable, Tenant t) {
        return switch (variable) {
            case "batchId", "sourceBatchId" -> t.batchId();
            case "studentId" -> t.studentId();
            case "trainerId" -> t.trainerId();
            case "companyId" -> t.companyId();
            case "topicId" -> t.topicId();
            case "moduleId" -> t.moduleId();
            case "submoduleId" -> t.submoduleId();
            case "requestId", "applicationId" -> t.requestId();
            case "projectId" -> t.projectId();
            case "id" -> {
                if (pattern.endsWith("/students/{id}/resend-invitation")) yield t.studentUserId();
                if (pattern.endsWith("/trainers/{id}/resend-invitation")) yield t.trainerUserId();
                if (pattern.startsWith("/api/v1/admin/bulk-uploads/")) yield t.uploadId();
                if (pattern.startsWith("/api/v1/admin/batches/")) yield t.batchId();
                if (pattern.startsWith("/api/v1/admin/students/") || pattern.startsWith("/api/v1/students/")) yield t.studentId();
                if (pattern.startsWith("/api/v1/admin/trainers/") || pattern.startsWith("/api/v1/trainers/")) yield t.trainerId();
                if (pattern.startsWith("/api/v1/admin/companies/")) yield t.companyId();
                throw new IllegalArgumentException("no id mapping for {id} in " + pattern);
            }
            default -> throw new IllegalArgumentException("no id mapping for {" + variable + "} in " + pattern);
        };
    }

    // ------------------------------------------------------------------
    // Fixture extras: rows TenantFixture does not seed

    private Tenant extras(TenantFixture f) {
        Long batchId = f.batchIds.get(0);
        Long studentId = f.studentIds.get(0);
        Long requestId = jdbc.queryForObject("""
                INSERT INTO enrollment_requests (batch_id, student_id, request_type, status, source, college_id)
                VALUES (?, ?, 'ADD', 'PENDING', 'STUDENT_APPLICATION', ?) RETURNING id
                """, Long.class, batchId, studentId, f.collegeId);
        Long projectId = jdbc.queryForObject("""
                INSERT INTO student_projects (student_id, title) VALUES (?, 'Fixture project') RETURNING id
                """, Long.class, studentId);
        // Real roles and a pending invitation, so resend-invitation has something
        // to reset. Without them a role or status check would answer 404 for its
        // own reasons, and the tenant check would never be exercised.
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT ?, id FROM roles WHERE name = 'STUDENT'
                UNION ALL SELECT ?, id FROM roles WHERE name = 'TRAINER'
                ON CONFLICT DO NOTHING
                """, f.studentUserIds.get(0), f.trainerUserId);
        jdbc.update("UPDATE users SET account_status = 'PENDING_SETUP' WHERE id IN (?, ?)",
                f.studentUserIds.get(0), f.trainerUserId);
        Long uploadId = jdbc.queryForObject("""
                INSERT INTO bulk_uploads (college_id, uploaded_by_user_id, entity_type, file_name, total_rows,
                                          successful_rows, failed_rows, status)
                VALUES (?, ?, 'STUDENT', 'fixture.csv', 0, 0, 0, 'COMPLETED') RETURNING id
                """, Long.class, f.collegeId, f.adminUserId);
        Long moduleId = jdbc.queryForObject(
                "SELECT min(id) FROM syllabus_modules WHERE batch_id = ?", Long.class, batchId);
        Long submoduleId = jdbc.queryForObject(
                "SELECT min(id) FROM syllabus_submodules WHERE module_id = ?", Long.class, moduleId);
        Long topicId = jdbc.queryForObject(
                "SELECT min(id) FROM syllabus_topics WHERE submodule_id = ?", Long.class, submoduleId);
        Long companyId = jdbc.queryForObject(
                "SELECT min(id) FROM companies WHERE college_id = ?", Long.class, f.collegeId);
        return new Tenant(f.collegeId, batchId, studentId, f.studentUserIds.get(0), f.trainerId,
                f.trainerUserId, f.adminUserId, companyId, moduleId, submoduleId, topicId, requestId, projectId,
                uploadId);
    }

    private void removeExtras(Long collegeId) {
        if (collegeId == null) {
            return;
        }
        jdbc.update("DELETE FROM student_projects WHERE student_id IN (SELECT id FROM students WHERE college_id = ?)", collegeId);
        jdbc.update("DELETE FROM student_skills WHERE student_id IN (SELECT id FROM students WHERE college_id = ?)", collegeId);
        jdbc.update("DELETE FROM enrollment_requests WHERE college_id = ?", collegeId);
        jdbc.update("DELETE FROM bulk_uploads WHERE college_id = ?", collegeId);
    }

    private String token(Long userId, String role) {
        return TestAuthentication.token(jwtService, userRepository.findById(userId).orElseThrow(), role);
    }

    /**
     * A digest of every row that belongs to one college, including the child rows
     * that reach it only through a parent. It changes if and only if their data
     * changes.
     */
    private String fingerprint(Long collegeId) {
        String batches = "(SELECT id FROM batches WHERE college_id = " + collegeId + ")";
        String students = "(SELECT id FROM students WHERE college_id = " + collegeId + ")";
        String modules = "(SELECT id FROM syllabus_modules WHERE college_id = " + collegeId + ")";
        String submodules = "(SELECT id FROM syllabus_submodules WHERE module_id IN " + modules + ")";
        Map<String, String> tables = new LinkedHashMap<>();
        tables.put("colleges", "id = " + collegeId);
        for (String table : List.of("batches", "students", "trainers", "companies", "users", "enrollments",
                "enrollment_requests", "topic_progress", "student_batch_progress", "syllabus_modules", "college_admins")) {
            tables.put(table, "college_id = " + collegeId);
        }
        tables.put("syllabus_submodules", "module_id IN " + modules);
        tables.put("syllabus_topics", "submodule_id IN " + submodules);
        tables.put("batch_trainers", "batch_id IN " + batches);
        tables.put("batch_companies", "batch_id IN " + batches);
        tables.put("feedback", "batch_id IN " + batches);
        tables.put("student_projects", "student_id IN " + students);
        tables.put("student_skills", "student_id IN " + students);

        StringBuilder sql = new StringBuilder("SELECT concat_ws(',' ");
        tables.forEach((table, where) -> sql.append(", (SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), '')) FROM ")
                .append(table).append(" t WHERE ").append(where).append(")"));
        sql.append(")");
        return jdbc.queryForObject(sql.toString(), String.class);
    }
}
