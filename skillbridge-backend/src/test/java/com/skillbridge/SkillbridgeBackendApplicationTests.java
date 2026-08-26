package com.skillbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full application context smoke test.
 *
 * <p>Requires a real PostgreSQL instance. Flyway owns the schema and the
 * migrations use Postgres-specific features throughout — {@code FILTER (WHERE)}
 * aggregates, partial indexes, a plpgsql trigger, pgvector — so an in-memory H2
 * cannot stand in for it, and pretending otherwise would mean a green test that
 * proves nothing.
 *
 * <p>It is therefore gated on {@code DATABASE_URL} being present: it runs in any
 * environment configured to reach a database, and skips cleanly on a developer
 * machine that is not. The permanent fix is Testcontainers, which spins up a
 * disposable {@code pgvector/pgvector:pg16} for the suite — that is Phase 11 of
 * the roadmap, and it is what turns this from one smoke test into a real
 * integration layer.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+",
        disabledReason = "needs a PostgreSQL instance; set DATABASE_URL to run")
class SkillbridgeBackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
