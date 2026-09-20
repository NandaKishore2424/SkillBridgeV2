package com.skillbridge.college;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TenantFixture;
import com.skillbridge.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating and changing a college: the first screen a system admin opens, and
 * at <b>5%</b> the least covered controller in the backend.
 *
 * <p>The test that found something is {@link #aPartialUpdateKeepsTheOtherFields()}.
 * {@code updateCollege} bound the JPA entity straight from the request body,
 * set its id, and saved it — a full replace. Every field the client left out
 * was written back as null, so renaming a college through the edit form
 * silently wiped its code, email, phone and address, and flipped its status
 * back to the entity default.
 *
 * <p>This is the same defect Phase 3 fixed in {@code BatchController.updateBatch},
 * which blanked a batch's name the same way. It was still here because nothing
 * asked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTest
class CollegeWriteTest {

    private static final String CODE = "COLWRITE";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    private TenantFixture fixture;
    private String systemAdminToken;
    private Long collegeId;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, CODE);
        fixture.seed(0, 0);
        collegeId = fixture.collegeId;

        jdbc.update("""
                UPDATE colleges SET email = ?, phone = '0123456789', address = 'One Long Road'
                WHERE id = ?
                """, "registrar@colwrite.example.invalid", collegeId);

        // A SYSTEM_ADMIN has no college of their own.
        User admin = userRepository.findById(fixture.adminUserId).orElseThrow();
        systemAdminToken = TestAuthentication.bearer(jwtService, admin, "SYSTEM_ADMIN");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM colleges WHERE code IN (?, ?)", CODE + "-NEW", CODE + "-X");
        fixture.remove();
    }

    // --------------------------------------------------------------- reading

    @Test
    @DisplayName("a college comes back with everything the detail screen renders")
    void readingACollege() throws Exception {
        mvc.perform(get("/api/v1/admin/colleges/{id}", collegeId)
                        .header("Authorization", systemAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE))
                .andExpect(jsonPath("$.email").value("registrar@colwrite.example.invalid"))
                .andExpect(jsonPath("$.phone").value("0123456789"))
                .andExpect(jsonPath("$.address").value("One Long Road"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("an unknown college is a 404 with the error model")
    void unknownCollegeIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/admin/colleges/{id}", 987654321L)
                        .header("Authorization", systemAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }

    // -------------------------------------------------------------- creating

    @Test
    @DisplayName("creating a college stores what was sent")
    void creatingACollege() throws Exception {
        mvc.perform(post("/api/v1/admin/colleges")
                        .header("Authorization", systemAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Coastal Institute","code":"%s-NEW",
                                 "email":"office@coastal.example.invalid","phone":"0999999999",
                                 "address":"Seafront"}
                                """.formatted(CODE)))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.name").value("Coastal Institute"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(field(CODE + "-NEW", "name")).isEqualTo("Coastal Institute");
    }

    @Test
    @DisplayName("a duplicate code is refused rather than answering 500")
    void duplicateCodeIsRefused() throws Exception {
        // `colleges.code` is unique. A constraint violation that escapes as a
        // 500 tells the admin nothing about what to change.
        mvc.perform(post("/api/v1/admin/colleges")
                        .header("Authorization", systemAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Clashing","code":"%s","email":"x@x.invalid"}
                                """.formatted(CODE)))
                .andExpect(status().is4xxClientError());
    }

    // -------------------------------------------------------------- updating

    @Test
    @DisplayName("a partial update keeps the fields it was not sent")
    void aPartialUpdateKeepsTheOtherFields() throws Exception {
        // Exactly what the edit form does when somebody changes only the name.
        mvc.perform(put("/api/v1/admin/colleges/{id}", collegeId)
                        .header("Authorization", systemAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed College\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed College"));

        // Binding the entity from the body and saving it is a full replace:
        // everything omitted comes back null, and the college loses its code —
        // which is how every one of its users is identified.
        assertThat(field(CODE, "name")).isEqualTo("Renamed College");
        assertThat(field(CODE, "email"))
                .as("the email was wiped by an update that never mentioned it")
                .isEqualTo("registrar@colwrite.example.invalid");
        assertThat(field(CODE, "phone")).isEqualTo("0123456789");
        assertThat(field(CODE, "address")).isEqualTo("One Long Road");
        assertThat(field(CODE, "status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an update cannot move a college to another id")
    void anUpdateCannotRepointTheId() throws Exception {
        Long other = insertCollege(CODE + "-X", "Other College");

        mvc.perform(put("/api/v1/admin/colleges/{id}", collegeId)
                        .header("Authorization", systemAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":%d,\"name\":\"Hijacked\"}".formatted(other)))
                .andExpect(status().isOk());

        // The id in the path wins. An id taken from the body would let one
        // college's edit form overwrite another college entirely.
        assertThat(field(CODE + "-X", "name"))
                .as("the body's id was honoured and a different college was overwritten")
                .isEqualTo("Other College");
        assertThat(field(CODE, "name")).isEqualTo("Hijacked");
    }

    @Test
    @DisplayName("updating an unknown college is a 404, not a create")
    void updatingAnUnknownCollege() throws Exception {
        mvc.perform(put("/api/v1/admin/colleges/{id}", 987654321L)
                        .header("Authorization", systemAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ghost\",\"code\":\"GHOST\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deactivating a college changes only its status")
    void deactivating() throws Exception {
        mvc.perform(patch("/api/v1/admin/colleges/{id}/status", collegeId)
                        .header("Authorization", systemAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        assertThat(field(CODE, "status")).isEqualTo("INACTIVE");
        assertThat(field(CODE, "email")).isEqualTo("registrar@colwrite.example.invalid");
    }

    // ----------------------------------------------------------------- roles

    @Test
    @DisplayName("a college admin cannot reach the colleges API at all")
    void collegeAdminIsRefused() throws Exception {
        User admin = userRepository.findById(fixture.adminUserId).orElseThrow();
        String collegeAdminToken = TestAuthentication.bearer(jwtService, admin, "COLLEGE_ADMIN");

        for (var request : Map.of(
                "get", get("/api/v1/admin/colleges/{id}", collegeId),
                "put", put("/api/v1/admin/colleges/{id}", collegeId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"No\"}")).entrySet()) {
            mvc.perform(request.getValue().header("Authorization", collegeAdminToken))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------ util

    private String field(String code, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM colleges WHERE code = ?", String.class, code);
    }

    private Long insertCollege(String code, String name) {
        return jdbc.queryForObject("""
                INSERT INTO colleges (name, code, email, phone, address, status, created_at, updated_at)
                VALUES (?, ?, 'x@x.invalid', '0000000000', 'n/a', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, name, code);
    }
}
