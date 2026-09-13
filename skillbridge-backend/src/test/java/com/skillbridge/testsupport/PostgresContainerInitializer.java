package com.skillbridge.testsupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Points the integration tier at a throwaway PostgreSQL instead of the live
 * Supabase database.
 *
 * <p>Registered for every test context through {@code spring.factories}, so no
 * test class has to know about it. Set {@code SKILLBRIDGE_TEST_DB=live} (or
 * {@code -Dskillbridge.test.db=live}) to opt back out and run against whatever
 * {@code application-local.yaml} provides.
 *
 * <h2>Why the default flipped</h2>
 *
 * <p>Running the integration tier against live had three costs, and only the
 * first was ever discussed.
 *
 * <ul>
 *   <li><b>It cannot run in CI.</b> The credentials are not there, and pointing
 *       CI at live would have every push compete for the pooler's 15 connections
 *       with the running application, which already holds 12.</li>
 *   <li><b>It writes to production data.</b> {@link TenantFixture} is careful and
 *       scoped, and it still inserts and deletes rows in the database the
 *       application serves from. A fixture that fails partway leaves them there;
 *       that has happened.</li>
 *   <li><b>It is not reliably green.</b> The first full {@code mvn verify} after
 *       the tiers were split ran 64 tests with one error, in
 *       {@code SearchTextMaintenanceTest}, whose root causes were
 *       {@code SocketException: Connection reset} and {@code An I/O error
 *       occurred while sending to the backend}. It passed on retry with nothing
 *       changed. One flake per ten-minute run is how people learn to re-run a
 *       suite until it is green, which is how a real failure gets re-run
 *       away.</li>
 * </ul>
 *
 * <h2>What makes the container faithful</h2>
 *
 * <p>{@code db/schema/baseline.sql} and {@code db/schema/reference-data.sql} are
 * copied into the image's init directory, which the official entrypoint runs in
 * filename order on first start. The baseline is not hand-maintained: it was
 * captured from live and diff-verified against it, and it reproduces live's
 * catalogue exactly on this image too, 551 objects digesting to
 * 31ef5166e257821574abad4853b7bdad on both. {@code scripts/verify-schema-baseline.sh}
 * re-checks that on demand.
 *
 * <p>The image is {@code pgvector/pgvector:pg17}: PostgreSQL 17, matching live's
 * 17.6, with pgvector for {@code industry_job_descriptions.embedding} and contrib
 * for {@code pg_trgm}. H2 would satisfy none of this, which is why the tier was
 * gated rather than faked for as long as it was.
 *
 * <h2>One container per JVM</h2>
 *
 * <p>The container is a static singleton started once and never stopped --
 * Testcontainers' Ryuk sidecar removes it when the JVM exits. Starting one per
 * class would multiply a ~3 s start by twenty. It is deliberately NOT
 * {@code withReuse(true)}: reuse leaves a container holding the previous run's
 * rows, and a test that passes because of data another test left behind is the
 * failure this whole tier is supposed to catch.
 */
public class PostgresContainerInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(PostgresContainerInitializer.class);

    /** Matches live: PostgreSQL 17.6. pgvector and contrib (pg_trgm) included. */
    private static final DockerImageName IMAGE = DockerImageName.parse("pgvector/pgvector:pg17")
            .asCompatibleSubstituteFor("postgres");

    private static final String OPT_OUT = "live";

    private static volatile PostgreSQLContainer<?> container;

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (usingLiveDatabase()) {
            log.info("skillbridge.test.db=live -- integration tests will use application-local.yaml");
            return;
        }

        PostgreSQLContainer<?> pg = startOnce();

        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("spring.datasource.url", pg.getJdbcUrl());
        overrides.put("spring.datasource.username", pg.getUsername());
        overrides.put("spring.datasource.password", pg.getPassword());
        overrides.put("spring.datasource.driver-class-name", pg.getDriverClassName());
        // The 3-connection cap in application-test.yaml exists to stay under the
        // Supabase pooler's 15. A local container has no such ceiling, but the
        // tier is sequential so nothing here needs more.
        overrides.put("spring.datasource.hikari.maximum-pool-size", 5);
        // Local: a connection opens in single-digit milliseconds rather than the
        // 1171 ms measured to ap-northeast-2, so the 20 s allowance for a
        // throttled link is no longer buying anything.
        overrides.put("spring.datasource.hikari.connection-timeout", 5000);

        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("testcontainers-postgres", overrides));
    }

    private static boolean usingLiveDatabase() {
        String property = System.getProperty("skillbridge.test.db");
        String environment = System.getenv("SKILLBRIDGE_TEST_DB");
        return OPT_OUT.equalsIgnoreCase(property) || OPT_OUT.equalsIgnoreCase(environment);
    }

    private static PostgreSQLContainer<?> startOnce() {
        PostgreSQLContainer<?> existing = container;
        if (existing != null) {
            return existing;
        }
        synchronized (PostgresContainerInitializer.class) {
            if (container == null) {
                container = create();
                container.start();
                log.info("integration tier is using {} at {}",
                        IMAGE, container.getJdbcUrl());
            }
            return container;
        }
    }

    private static PostgreSQLContainer<?> create() {
        Path schemaDir = repositoryRoot().resolve("db/schema");
        Path baseline = schemaDir.resolve("baseline.sql");
        Path reference = schemaDir.resolve("reference-data.sql");

        for (Path required : new Path[]{baseline, reference}) {
            if (!Files.isReadable(required)) {
                throw new IllegalStateException(
                        "cannot build a test database: " + required + " is missing or unreadable. "
                                + "It is the only copy of this schema outside the Supabase project; "
                                + "re-capture it with scripts/verify-schema-baseline.sh as the guide.");
            }
        }

        // The official postgres entrypoint runs /docker-entrypoint-initdb.d in
        // filename order on first initialisation, so the numeric prefixes are
        // load-bearing: reference-data.sql inserts into a table baseline.sql
        // creates.
        return new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("skillbridge")
                .withUsername("skillbridge")
                .withPassword("skillbridge")
                .withCopyFileToContainer(MountableFile.forHostPath(baseline),
                        "/docker-entrypoint-initdb.d/01-baseline.sql")
                .withCopyFileToContainer(MountableFile.forHostPath(reference),
                        "/docker-entrypoint-initdb.d/02-reference-data.sql");
    }

    /**
     * Walks up from the working directory to the directory holding {@code db/schema}.
     *
     * <p>Surefire and Failsafe run with the module directory as the working
     * directory, so this is normally one level up -- but an IDE may launch a test
     * from the repository root instead, and hard-coding {@code ../} fails there
     * with a message about a missing file rather than about the working
     * directory, which is a bad half-hour.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve("db/schema"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "could not find db/schema above " + Path.of("").toAbsolutePath()
                        + " -- run the tests from the repository or the backend module");
    }
}
