package com.skillbridge.batch;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.batch.service.BatchService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TestAuthentication;
import com.skillbridge.testsupport.TenantFixture;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating and changing a batch, over HTTP, since {@code BatchService} took the
 * work from the controller (2026-09-20).
 *
 * <p>The three that used to pass silently: an unparseable date was dropped and
 * the batch saved without it, a body without a name blanked the stored name,
 * and an unknown status reached the database's CHECK constraint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTest
class BatchWriteTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    private TenantFixture fixture;
    private String token;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, "BATCHWRITE");
        fixture.seed(0, 0);
        token = TestAuthentication.bearer(jwtService,
                userRepository.findById(fixture.adminUserId).orElseThrow(), "COLLEGE_ADMIN");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM batches WHERE college_id = ?", fixture.collegeId);
        fixture.remove();
    }

    @Test
    @DisplayName("a date that is not a date is refused, naming the field; it used to be dropped silently")
    void unparseableDateIsRefused() throws Exception {
        create("{\"name\":\"Spring 2026\",\"startDate\":\"20th of March\",\"endDate\":\"2026-06-30\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("startDate is not a date")))
                .andExpect(jsonPath("$.message").value(containsString("yyyy-MM-dd")));

        assertThat(batchCount()).as("nothing was created").isZero();
    }

    @Test
    @DisplayName("both date formats the old code accepted still work")
    void bothDateFormatsWork() throws Exception {
        long id = createdId("{\"name\":\"US format\",\"startDate\":\"03/20/2026\",\"endDate\":\"2026-06-30\"}");

        assertThat(jdbc.queryForObject("SELECT start_date::text FROM batches WHERE id = ?", String.class, id))
                .isEqualTo("2026-03-20");
    }

    @Test
    @DisplayName("an end before the start is refused")
    void endBeforeStart() throws Exception {
        create("{\"name\":\"Backwards\",\"startDate\":\"2026-06-30\",\"endDate\":\"2026-03-20\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("endDate cannot be before startDate")));
    }

    @Test
    @DisplayName("an unknown status is refused with the ones that exist, not a constraint violation")
    void unknownStatus() throws Exception {
        create("{\"name\":\"Odd\",\"status\":\"STARTED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unknown status")))
                .andExpect(jsonPath("$.message").value(containsString("UPCOMING")));
    }

    @Test
    @DisplayName("a name is required to create, and cannot be blanked by an update")
    void nameSurvivesAPartialUpdate() throws Exception {
        create("{\"description\":\"no name\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        long id = createdId("{\"name\":\"Keeps its name\",\"description\":\"first\"}");

        // What the screen sends when only the description changed. This used to
        // copy a null name over the stored one.
        mvc.perform(auth(put("/api/v1/admin/batches/{id}", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"second\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keeps its name"))
                .andExpect(jsonPath("$.description").value("second"));

        mvc.perform(auth(put("/api/v1/admin/batches/{id}", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Name cannot be blank")));
    }

    @Test
    @DisplayName("a status change is accepted in any case, and an unknown one is a 400")
    void statusChange() throws Exception {
        long id = createdId("{\"name\":\"Status changes\"}");

        mvc.perform(auth(patch("/api/v1/admin/batches/{id}/status", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"active\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(auth(patch("/api/v1/admin/batches/{id}/status", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"GONE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the statuses the service allows are exactly the ones the database allows")
    void statusesMatchTheDatabase() {
        String check = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'batches_status_check'",
                String.class);

        Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(check);
        Set<String> inDatabase = new LinkedHashSet<>();
        while (quoted.find()) {
            inDatabase.add(quoted.group(1));
        }
        assertThat(inDatabase).as("the constraint must list statuses: %s", check).isNotEmpty();
        assertThat(BatchService.STATUSES)
                .as("BatchService.STATUSES and batches_status_check must not drift apart")
                .isEqualTo(inDatabase);
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(auth(post("/api/v1/admin/batches"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long createdId(String body) throws Exception {
        String response = create(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher id = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(response);
        assertThat(id.find()).as("created batch id in %s", response).isTrue();
        return Long.parseLong(id.group(1));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", token);
    }

    private Integer batchCount() {
        return jdbc.queryForObject("SELECT count(*) FROM batches WHERE college_id = ?", Integer.class,
                fixture.collegeId);
    }
}
