package com.skillbridge.syllabus;

import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.common.cache.L1CacheConfig;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.syllabus.dto.CreateModuleRequest;
import com.skillbridge.syllabus.dto.SyllabusModuleDTO;
import com.skillbridge.syllabus.service.SyllabusService;
import com.skillbridge.testsupport.QueryCountAssertion;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The curriculum cache has to serve, has to let go, and — the one that matters —
 * must not answer a question the caller was not allowed to ask.
 *
 * <p>{@code getCurriculumByBatchId} calls {@code requireBatch} and then assembles
 * the tree. Put {@code @Cacheable} on that method and the tenant check is cached
 * away with everything else: on a hit the body never runs, so a trainer from
 * another college gets the tree instead of a 404. Nothing about the annotation
 * looks wrong — batch ids are globally unique, so the key is genuinely
 * collision-free, and the leak is the skipped check rather than a shared key.
 *
 * <p>That is why the assembly lives in {@code CurriculumReader} and the check
 * stays in the caller, and why {@link #aWarmCacheStillRefusesAnotherCollege} is
 * the most important test in this file. It fails against the obvious
 * implementation, which is the whole point of writing it.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+",
        disabledReason = "needs a PostgreSQL instance; set DATABASE_URL to run")
class CurriculumCacheTest {

    private static final String OWNER = "CURRICOWNER";
    private static final String STRANGER = "CURRICOTHER";

    @Autowired private SyllabusService syllabusService;
    @Autowired private QueryCountAssertion queries;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    private TenantFixture owner;
    private TenantFixture stranger;
    private Long batchId;

    @BeforeEach
    void seed() {
        owner = new TenantFixture(jdbc, OWNER);
        owner.seed(1, 1, 2);
        stranger = new TenantFixture(jdbc, STRANGER);
        stranger.seed(0, 0);
        batchId = owner.batchIds.get(0);
        clearCache();
        asTrainerOf(owner);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        owner.remove();
        stranger.remove();
        clearCache();
    }

    private void clearCache() {
        cacheManager.getCache(L1CacheConfig.CURRICULUM).clear();
    }

    private void asTrainerOf(TenantFixture tenant) {
        SecurityContextHolder.clearContext();
        Role role = new Role();
        role.setName("TRAINER");
        User user = User.builder()
                .id(tenant.trainerUserId).email("curriculum@example.invalid")
                .collegeId(tenant.collegeId).isActive(true).roles(Set.of(role)).build();
        AuthenticatedUser principal = new AuthenticatedUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("the second read costs only the tenant check, not the tree")
    void aSecondReadRereadsNothingButTheCheck() {
        var cold = queries.countQueries(() -> syllabusService.getCurriculumByBatchId(batchId));
        var warm = queries.countQueries(() -> syllabusService.getCurriculumByBatchId(batchId));

        assertThat(cold.value())
                .as("an empty tree would make every count below pass for the wrong reason")
                .hasSize(2);

        assertThat(cold.queryCount())
                .as("the batch lookup plus the two tree queries")
                .isEqualTo(3);

        assertThat(warm.queryCount())
                .as("""
                    One, and exactly one: the tenant check still reads the batch, and the \
                    two tree queries are gone. Three would mean the cache is inert -- a \
                    self-invocation, an unproxied bean, no @EnableCaching -- and zero would \
                    mean the authorisation had been cached away with it.""")
                .isEqualTo(1);

        assertThat(warm.value()).hasSameSizeAs(cold.value());
    }

    @Test
    @DisplayName("a warm cache still refuses another college's trainer")
    void aWarmCacheStillRefusesAnotherCollege() {
        List<SyllabusModuleDTO> mine = syllabusService.getCurriculumByBatchId(batchId);
        assertThat(mine).as("the cache has to be warm for this test to mean anything").isNotEmpty();

        asTrainerOf(stranger);

        assertThatThrownBy(() -> syllabusService.getCurriculumByBatchId(batchId))
                .as("""
                    A cache hit must not be an authorisation bypass. This is what \
                    @Cacheable on getCurriculumByBatchId would have produced: the body \
                    never runs on a hit, so requireBatch never runs either, and another \
                    college's curriculum comes back with a 200.""")
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("adding a module is visible on the very next read")
    void aWriteEvicts() {
        assertThat(syllabusService.getCurriculumByBatchId(batchId)).hasSize(2);

        CreateModuleRequest request = new CreateModuleRequest();
        request.setName("Added after the cache was warm");
        request.setDisplayOrder(99);
        syllabusService.createModule(batchId, request);

        assertThat(syllabusService.getCurriculumByBatchId(batchId))
                .as("""
                    The trainer saves a module and the students keep seeing the old \
                    syllabus for fifteen minutes. Nothing fails; the endpoint answers 200 \
                    with the tree as it was.""")
                .hasSize(3);
    }
}
