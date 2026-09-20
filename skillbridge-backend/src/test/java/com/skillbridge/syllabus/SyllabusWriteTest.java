package com.skillbridge.syllabus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Building a curriculum: modules, sub-modules, topics, and copying one batch's
 * onto another. {@code syllabus.service} was at <b>41.8%</b> of its lines, and
 * this is the screen a trainer spends the demo in.
 *
 * <p>Two properties are worth the most here.
 *
 * <p><b>Display order is what the tree is sorted by</b>, so a duplicate is not
 * a cosmetic problem — two modules at order 2 come back in whatever order the
 * database felt like, and the same trainer sees a different curriculum on each
 * refresh. The service refuses it; nothing checked that it does.
 *
 * <p><b>Copying refuses a non-empty target</b> rather than merging. Merging
 * raises questions the feature does not answer — what happens to display
 * orders, whether a same-named module is the same module — and silently
 * interleaving two curricula is far worse than declining. It also deliberately
 * does not copy completion flags: those describe what the <em>source</em>
 * batch's trainer has taught, and carrying them over would show a brand-new
 * cohort as already part-way through.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTest
class SyllabusWriteTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    private TenantFixture home;
    private TenantFixture other;

    /** Has a curriculum, from the fixture. */
    private Long sourceBatchId;
    /** Deliberately empty. */
    private Long emptyBatchId;
    private String trainerToken;

    @BeforeEach
    void seed() {
        // Two batches, one module each; the second is emptied so there is a
        // genuine copy target.
        home = new TenantFixture(jdbc, "SYLIT");
        home.seed(2, 1, 1);
        sourceBatchId = home.batchIds.get(0);
        emptyBatchId = home.batchIds.get(1);
        clearCurriculum(emptyBatchId);

        other = new TenantFixture(jdbc, "SYLOTHER");
        other.seed(1, 1, 1);

        trainerToken = bearer(home.trainerUserId, "TRAINER");
    }

    @AfterEach
    void cleanUp() {
        home.remove();
        other.remove();
    }

    // ------------------------------------------------------------- structure

    @Test
    @DisplayName("a module, a sub-module and a topic come back in the tree")
    void buildingTheTree() throws Exception {
        long moduleId = idOf(post("/api/v1/batches/{b}/syllabus/modules", emptyBatchId)
                .content("""
                        {"name":"Foundations","description":"Week 1-2","displayOrder":1}
                        """));
        long submoduleId = idOf(post("/api/v1/syllabus/modules/{m}/submodules", moduleId)
                .content("""
                        {"name":"Java basics","displayOrder":1}
                        """));
        idOf(post("/api/v1/syllabus/submodules/{s}/topics", submoduleId)
                .content("""
                        {"name":"Collections","displayOrder":1}
                        """));

        mvc.perform(get("/api/v1/batches/{b}/syllabus", emptyBatchId)
                        .header("Authorization", trainerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Foundations"))
                .andExpect(jsonPath("$[0].submodules[0].name").value("Java basics"))
                .andExpect(jsonPath("$[0].submodules[0].topics[0].name").value("Collections"));
    }

    @Test
    @DisplayName("two modules cannot share a display order")
    void duplicateModuleOrderRefused() throws Exception {
        idOf(post("/api/v1/batches/{b}/syllabus/modules", emptyBatchId)
                .content("{\"name\":\"First\",\"displayOrder\":1}"));

        // Not cosmetic: display order is the sort key for the whole tree, so a
        // duplicate means the curriculum comes back in a different order on
        // different refreshes.
        mvc.perform(post("/api/v1/batches/{b}/syllabus/modules", emptyBatchId)
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Second\",\"displayOrder\":1}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(moduleCount(emptyBatchId)).isEqualTo(1);
    }

    @Test
    @DisplayName("nor two sub-modules of the same module")
    void duplicateSubmoduleOrderRefused() throws Exception {
        long moduleId = idOf(post("/api/v1/batches/{b}/syllabus/modules", emptyBatchId)
                .content("{\"name\":\"Only\",\"displayOrder\":1}"));
        idOf(post("/api/v1/syllabus/modules/{m}/submodules", moduleId)
                .content("{\"name\":\"First\",\"displayOrder\":1}"));

        mvc.perform(post("/api/v1/syllabus/modules/{m}/submodules", moduleId)
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Second\",\"displayOrder\":1}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("renaming a module keeps its own display order free")
    void renamingDoesNotCollideWithItself() throws Exception {
        long moduleId = idOf(post("/api/v1/batches/{b}/syllabus/modules", emptyBatchId)
                .content("{\"name\":\"Before\",\"displayOrder\":1}"));

        // The duplicate check has to exclude the row being edited, or a module
        // can never be renamed without also moving it.
        mvc.perform(put("/api/v1/syllabus/modules/{m}", moduleId)
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"After\",\"displayOrder\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("After"));
    }

    @Test
    @DisplayName("deleting a module takes its sub-modules and topics with it")
    void deletingAModuleCascades() throws Exception {
        long moduleId = idOf(post("/api/v1/batches/{b}/syllabus/modules", emptyBatchId)
                .content("{\"name\":\"Doomed\",\"displayOrder\":1}"));
        long submoduleId = idOf(post("/api/v1/syllabus/modules/{m}/submodules", moduleId)
                .content("{\"name\":\"Doomed sub\",\"displayOrder\":1}"));
        idOf(post("/api/v1/syllabus/submodules/{s}/topics", submoduleId)
                .content("{\"name\":\"Doomed topic\",\"displayOrder\":1}"));

        mvc.perform(delete("/api/v1/syllabus/modules/{m}", moduleId)
                        .header("Authorization", trainerToken))
                .andExpect(status().isNoContent());

        // Orphaned sub-modules and topics are invisible in the tree and
        // permanent in the table.
        assertThat(moduleCount(emptyBatchId)).isZero();
        assertThat(submoduleCount(submoduleId)).isZero();
    }

    // ------------------------------------------------------------------ copy

    @Test
    @DisplayName("copying reproduces the source tree on the empty batch")
    void copyingReproducesTheTree() throws Exception {
        mvc.perform(post("/api/v1/batches/{t}/syllabus/copy-from/{s}", emptyBatchId, sourceBatchId)
                        .header("Authorization", trainerToken))
                .andExpect(status().isOk());

        // The fixture's curriculum is 1 module x 2 sub-modules x 2 topics.
        assertThat(moduleCount(emptyBatchId)).isEqualTo(moduleCount(sourceBatchId));
        assertThat(topicCount(emptyBatchId)).isEqualTo(4);
    }

    @Test
    @DisplayName("copying does not carry the source's completion flags across")
    void copyingLeavesCompletionBehind() throws Exception {
        jdbc.update("""
                UPDATE syllabus_topics SET is_completed = true, completed_at = now()
                WHERE submodule_id IN (
                    SELECT sm.id FROM syllabus_submodules sm
                    JOIN syllabus_modules m ON sm.module_id = m.id
                    WHERE m.batch_id = ?)
                """, sourceBatchId);

        mvc.perform(post("/api/v1/batches/{t}/syllabus/copy-from/{s}", emptyBatchId, sourceBatchId)
                        .header("Authorization", trainerToken))
                .andExpect(status().isOk());

        // Those flags describe what the *source* batch's trainer has taught.
        // Carried over, a brand-new cohort opens the screen already shown as
        // part-way through a course that has not started.
        assertThat(completedTopicCount(emptyBatchId))
                .as("the new batch inherited the old batch's progress")
                .isZero();
    }

    @Test
    @DisplayName("copying onto a batch that already has a curriculum is refused")
    void copyingOntoANonEmptyBatchRefused() throws Exception {
        // Copy once so the target is genuinely non-empty, then copy again.
        //
        // The first version of this test copied *from* the empty batch onto the
        // non-empty one and accepted any 4xx. That passes with the
        // CURRICULUM_NOT_EMPTY check deleted, because the source-is-empty check
        // answers instead — it went green when the rule it names was removed.
        // Both batches have a curriculum here, so only the right rule can fire.
        mvc.perform(post("/api/v1/batches/{t}/syllabus/copy-from/{s}", emptyBatchId, sourceBatchId)
                        .header("Authorization", trainerToken))
                .andExpect(status().isOk());
        int afterFirstCopy = moduleCount(emptyBatchId);
        assertThat(afterFirstCopy).isPositive();

        mvc.perform(post("/api/v1/batches/{t}/syllabus/copy-from/{s}", emptyBatchId, sourceBatchId)
                        .header("Authorization", trainerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CURRICULUM_NOT_EMPTY"));

        // Silently interleaving two curricula is far worse than declining.
        assertThat(moduleCount(emptyBatchId))
                .as("the second copy merged into the first")
                .isEqualTo(afterFirstCopy);
    }

    @Test
    @DisplayName("copying a batch onto itself is refused")
    void copyingOntoItselfRefused() throws Exception {
        mvc.perform(post("/api/v1/batches/{t}/syllabus/copy-from/{s}", sourceBatchId, sourceBatchId)
                        .header("Authorization", trainerToken))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("copying from an empty batch says so rather than succeeding with nothing")
    void copyingFromAnEmptyBatchRefused() throws Exception {
        Long secondEmpty = insertEmptyBatch();

        mvc.perform(post("/api/v1/batches/{t}/syllabus/copy-from/{s}", secondEmpty, emptyBatchId)
                        .header("Authorization", trainerToken))
                .andExpect(status().isUnprocessableEntity());
    }

    // --------------------------------------------------------------- tenancy

    @Test
    @DisplayName("a trainer cannot add a module to another college's batch")
    void cannotWriteIntoAnotherCollege() throws Exception {
        mvc.perform(post("/api/v1/batches/{b}/syllabus/modules", other.batchIds.get(0))
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Injected\",\"displayOrder\":99}"))
                .andExpect(status().isNotFound());

        assertThat(moduleCount(other.batchIds.get(0))).isEqualTo(1);
    }

    @Test
    @DisplayName("nor copy another college's curriculum")
    void cannotCopyAnotherCollegesCurriculum() throws Exception {
        mvc.perform(post("/api/v1/batches/{t}/syllabus/copy-from/{s}", emptyBatchId, other.batchIds.get(0))
                        .header("Authorization", trainerToken))
                .andExpect(status().isNotFound());

        assertThat(moduleCount(emptyBatchId)).isZero();
    }

    @Test
    @DisplayName("nor read it")
    void cannotReadAnotherCollegesCurriculum() throws Exception {
        mvc.perform(get("/api/v1/batches/{b}/syllabus", other.batchIds.get(0))
                        .header("Authorization", trainerToken))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ util

    /** POSTs as the trainer with a JSON body, and returns the created id. */
    private long idOf(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        assertThat(node.hasNonNull("id")).as("no id in %s", body).isTrue();
        return node.get("id").asLong();
    }

    private String bearer(Long userId, String role) {
        User user = userRepository.findById(userId).orElseThrow();
        return TestAuthentication.bearer(jwtService, user, role);
    }

    private void clearCurriculum(Long batchId) {
        jdbc.update("""
                DELETE FROM syllabus_topics WHERE submodule_id IN (
                    SELECT sm.id FROM syllabus_submodules sm
                    JOIN syllabus_modules m ON sm.module_id = m.id WHERE m.batch_id = ?)
                """, batchId);
        jdbc.update("""
                DELETE FROM syllabus_submodules WHERE module_id IN (
                    SELECT id FROM syllabus_modules WHERE batch_id = ?)
                """, batchId);
        jdbc.update("DELETE FROM syllabus_modules WHERE batch_id = ?", batchId);
    }

    private Long insertEmptyBatch() {
        Long id = jdbc.queryForObject("""
                INSERT INTO batches (college_id, name, description, status, start_date, end_date,
                                     created_at, updated_at, version)
                VALUES (?, 'Second Empty', 'no curriculum', 'ACTIVE', CURRENT_DATE, CURRENT_DATE + 30,
                        now(), now(), 0)
                RETURNING id
                """, Long.class, home.collegeId);
        home.batchIds.add(id);
        return id;
    }

    private int moduleCount(Long batchId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM syllabus_modules WHERE batch_id = ?", Integer.class, batchId);
    }

    private int submoduleCount(Long submoduleId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM syllabus_submodules WHERE id = ?", Integer.class, submoduleId);
    }

    private int topicCount(Long batchId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM syllabus_topics t
                JOIN syllabus_submodules sm ON t.submodule_id = sm.id
                JOIN syllabus_modules m ON sm.module_id = m.id
                WHERE m.batch_id = ?
                """, Integer.class, batchId);
    }

    private int completedTopicCount(Long batchId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM syllabus_topics t
                JOIN syllabus_submodules sm ON t.submodule_id = sm.id
                JOIN syllabus_modules m ON sm.module_id = m.id
                WHERE m.batch_id = ? AND t.is_completed = true
                """, Integer.class, batchId);
    }
}
