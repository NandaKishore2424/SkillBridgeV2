package com.skillbridge.testsupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.EnumerablePropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

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
 * <p>The container starts empty and <b>Flyway builds the schema</b> when the
 * first Spring context starts, from the same {@code db/migration} files that
 * build every other database (reinstated 2026-09-19). So the tests exercise the
 * migrations themselves, not a copy of the schema that could drift from them.
 * V1 was generated from {@code db/schema/baseline.sql}, the capture verified
 * identical to the retired Supabase database.
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
        context.getEnvironment().getPropertySources().addFirst(new LazyContainerProperties());
    }

    /**
     * Supplies the datasource properties, starting the container on first ask.
     *
     * <p>This initializer is registered for <i>every</i> test context, and most
     * slice tests do not want a database: {@code @WebMvcTest} excludes the
     * datasource auto-configuration entirely, so nothing in such a context ever
     * resolves {@code spring.datasource.url}. Starting the container eagerly
     * would add a container start to every web-slice test and put a database
     * behind tests written specifically to prove they do not need one.
     *
     * <p>So the values are behind a supplier. Ask for one and the container
     * starts; never ask and it never does.
     */
    private static final class LazyContainerProperties extends EnumerablePropertySource<Object> {

        private static final Map<String, Function<PostgreSQLContainer<?>, Object>> VALUES =
                new LinkedHashMap<>(Map.of(
                        "spring.datasource.url", PostgreSQLContainer::getJdbcUrl,
                        "spring.datasource.username", PostgreSQLContainer::getUsername,
                        "spring.datasource.password", PostgreSQLContainer::getPassword,
                        "spring.datasource.driver-class-name", PostgreSQLContainer::getDriverClassName,
                        // The 3-connection cap in application-test.yaml exists to
                        // stay under the Supabase pooler's 15. A local container
                        // has no such ceiling; the tier is sequential regardless.
                        "spring.datasource.hikari.maximum-pool-size", pg -> 5,
                        // Local connections open in single-digit milliseconds
                        // rather than the 1171 ms measured to ap-northeast-2, so
                        // the 20 s allowance for a throttled link buys nothing.
                        "spring.datasource.hikari.connection-timeout", pg -> 5000));

        LazyContainerProperties() {
            super("testcontainers-postgres", new Object());
        }

        @Override
        public String[] getPropertyNames() {
            return VALUES.keySet().toArray(String[]::new);
        }

        @Override
        public Object getProperty(String name) {
            Function<PostgreSQLContainer<?>, Object> value = VALUES.get(name);
            return value == null ? null : value.apply(startOnce());
        }
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
        // Empty on purpose: Flyway (spring.flyway in application.yaml) creates the
        // schema and reference data on the first context start.
        return new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("skillbridge")
                .withUsername("skillbridge")
                .withPassword("skillbridge");
    }
}
