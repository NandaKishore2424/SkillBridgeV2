package com.skillbridge.idempotency;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The five things {@code @Idempotent} promises, over real HTTP.
 *
 * <p>Deliberately not MockMvc. The mechanism is a servlet filter that buffers
 * the request body and wraps the response, plus an interceptor that reads both
 * — most of what could go wrong lives in that plumbing rather than in the
 * logic, and MockMvc does not exercise it the way a container does.
 *
 * <p>{@code POST /admin/batches} is the subject because {@code batches} has no
 * unique constraint of any kind: without this mechanism, sending the same
 * create twice leaves two identical rows and nothing to tell them apart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@IntegrationTest
class IdempotencyContractTest {

    private static final String COLLEGE_CODE = "IDEMPOTENT";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    private TenantFixture fixture;
    private String token;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(0, 0);
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE name = 'COLLEGE_ADMIN'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                fixture.adminUserId, roleId);
        User admin = userRepository.findById(fixture.adminUserId).orElseThrow();
        token = jwtService.generateAccessToken(admin, "COLLEGE_ADMIN",
                java.util.Set.of("COLLEGE_ADMIN"), false);
    }

    @AfterEach
    void cleanUp() {
        // idempotency_keys.user_id cascades on user delete, so the fixture's own
        // teardown removes the records this test wrote.
        fixture.remove();
    }

    private ResponseEntity<String> createBatch(String key, String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        String body = """
                {"name":"%s","description":"idempotency test","startDate":"2026-03-01","endDate":"2026-06-01"}
                """.formatted(name);
        return rest.exchange("http://localhost:" + port + "/api/v1/admin/batches",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private int batchCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM batches WHERE college_id = ?", Integer.class, fixture.collegeId);
    }

    @Test
    @DisplayName("the header is required, and its absence is a 400 rather than an unprotected write")
    void headerIsRequired() {
        ResponseEntity<String> response = createBatch(null, "No Key");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(batchCount())
                .as("a rejected request must not have created anything")
                .isZero();
    }

    @Test
    @DisplayName("two identical creates with one key create one batch; the second is replayed")
    void secondIdenticalRequestIsReplayed() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = createBatch(key, "Replayed Batch");
        ResponseEntity<String> second = createBatch(key, "Replayed Batch");

        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getFirst("Idempotent-Replay"))
                .as("a replay has to be distinguishable from a fresh execution")
                .isEqualTo("true");
        assertThat(first.getHeaders().getFirst("Idempotent-Replay"))
                .as("the original is not a replay")
                .isNull();
        assertThat(batchCount())
                .as("this is the whole point: one row, not two")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different body is refused with 422")
    void sameKeyDifferentBodyIsRefused() {
        String key = UUID.randomUUID().toString();
        createBatch(key, "Original Name");

        ResponseEntity<String> reused = createBatch(key, "A Completely Different Batch");

        assertThat(reused.getStatusCode().value()).isEqualTo(422);
        assertThat(reused.getBody()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(batchCount())
                .as("the second create must not run, and must not be served the first one's response")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent identical requests: one proceeds, the other gets 409 with Retry-After")
    void concurrentRequestsSerialise() throws Exception {
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<ResponseEntity<String>>> both = List.of(
                    () -> createBatch(key, "Racing Batch"),
                    () -> createBatch(key, "Racing Batch"));
            List<Future<ResponseEntity<String>>> futures = pool.invokeAll(both);
            ResponseEntity<String> a = futures.get(0).get();
            ResponseEntity<String> b = futures.get(1).get();

            // createBatch answers 200, not 201 -- asserting the endpoint's actual
            // contract rather than the one a create "should" have.
            List<Integer> codes = List.of(a.getStatusCode().value(), b.getStatusCode().value());
            assertThat(codes).as("exactly one request may do the work").contains(200);

            // The loser is either told to wait (still running) or handed the
            // finished response (already committed). Both are correct; what is
            // not correct is a second batch, or a 500.
            assertThat(codes).as("a race must never surface as a server error").doesNotContain(500);
            ResponseEntity<String> loser = a.getHeaders().getFirst("Idempotent-Replay") != null
                    || a.getStatusCode().value() == 409 ? a : b;
            if (loser.getStatusCode().value() == 409) {
                assertThat(loser.getHeaders().getFirst("Retry-After"))
                        .as("a 409 that does not say when to retry is a dead end")
                        .isNotNull();
                assertThat(loser.getBody()).contains("REQUEST_IN_PROGRESS");
            } else {
                assertThat(loser.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
            }
            assertThat(batchCount())
                    .as("a race must not produce two rows -- the unique constraint is what guarantees it")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an expired record is ignored, without waiting for the purge job")
    void expiredRecordsAreIgnored() {
        String key = UUID.randomUUID().toString();
        createBatch(key, "First Batch");
        assertThat(batchCount()).isEqualTo(1);
        // Without this the test passes even with the mechanism switched off:
        // two plain creates also leave two batches and no replay header.
        assertThat(countKeys())
                .as("the first request must have left a record, or nothing here is being tested")
                .isEqualTo(1);

        // Expire it in place. The interceptor must decide on expires_at, not on
        // whether IdempotencyPurgeJob has run -- otherwise the guarantee
        // silently depends on a cron schedule.
        jdbc.update("UPDATE idempotency_keys SET expires_at = now() - interval '1 hour' WHERE user_id = ?",
                fixture.adminUserId);

        ResponseEntity<String> afterExpiry = createBatch(key, "First Batch");

        assertThat(afterExpiry.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(afterExpiry.getHeaders().getFirst("Idempotent-Replay"))
                .as("an expired key is a fresh key, not a replay")
                .isNull();
        assertThat(batchCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the purge job deletes expired records and leaves live ones")
    void purgeRemovesOnlyExpired() {
        createBatch(UUID.randomUUID().toString(), "Live Key Batch");
        createBatch(UUID.randomUUID().toString(), "Expired Key Batch");

        jdbc.update("""
                UPDATE idempotency_keys SET expires_at = now() - interval '1 hour'
                WHERE user_id = ? AND response_body LIKE '%Expired Key Batch%'
                """, fixture.adminUserId);

        int before = countKeys();
        assertThat(before).isEqualTo(2);

        int deleted = purge();

        assertThat(deleted).isEqualTo(1);
        assertThat(countKeys()).isEqualTo(1);
    }

    private int countKeys() {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE user_id = ?",
                Integer.class, fixture.adminUserId);
    }

    @Autowired private com.skillbridge.common.idempotency.IdempotencyService idempotencyService;

    private int purge() {
        return idempotencyService.purgeExpired();
    }
}
