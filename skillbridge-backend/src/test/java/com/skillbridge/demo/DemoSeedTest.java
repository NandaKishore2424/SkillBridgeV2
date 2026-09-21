package com.skillbridge.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.common.config.RabbitMQConfig;
import com.skillbridge.progress.domain.ProgressStatus;
import com.skillbridge.shared.messaging.EventEnvelope;
import com.skillbridge.shared.messaging.EventType;
import com.skillbridge.testsupport.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@code scripts/db/demo-seed.sql} and checks the database it produces.
 *
 * <p>The seed is what stands behind every live demonstration of this project,
 * and it is the only substantial piece of it that no test previously touched.
 * It is also the piece most able to fail quietly: a join that matches nothing
 * inserts nothing, and a dataset missing a third of its rows still looks like a
 * dataset. The seed carries its own checks for that (section 12, which names
 * the offending line); this class checks what those cannot — that the result is
 * a database the <em>application</em> can use.
 *
 * <h2>Its own container</h2>
 *
 * <p>The rest of the integration tier shares one PostgreSQL for the whole JVM.
 * This test cannot: the seed opens with {@code DELETE FROM colleges}, and
 * running that in the shared container would empty another class's fixture
 * mid-suite. So it starts a container of its own, builds the schema with the
 * same Flyway migrations as everything else, and stops it afterwards.
 *
 * <h2>Why psql, and not JDBC</h2>
 *
 * <p>The file is executed by the real {@code psql} inside the container, with a
 * real {@code :demo_password} psql variable, because that is how it is run for a
 * demo. (seed-demo.sh sets it with {@code \getenv} so the password is never an
 * argument; {@code -v} here sets the same variable, and the test's password is
 * not a secret.)
 * Reading it into JDBC would mean stripping the backslash commands and
 * substituting the variable by hand — testing a transformation of the file
 * rather than the file.
 */
@IntegrationTest
class DemoSeedTest {

    /** Repository-relative: the tests run with the backend module as the working directory. */
    private static final Path SEED = Path.of("..", "scripts", "db", "demo-seed.sql");

    /**
     * Generated per run and never written down. Nothing about the seed should
     * depend on which password it is given, and a fixed one in this file would
     * be a demo credential in git, which is the habit the script exists to end.
     */
    private static final String PASSWORD = "Seeded-" + UUID.randomUUID();

    private static final int EXPECTED_STUDENTS = 16;

    private static PostgreSQLContainer<?> db;
    private static Connection connection;

    @BeforeAll
    static void runTheSeed() throws Exception {
        assertThat(SEED).as("the seed script this test exists to check").exists();

        db = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
        db.start();

        Flyway.configure()
                .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // seed-demo.sh installs pgcrypto before running the file and drops it
        // afterwards. Here only the first half matters: the container is thrown
        // away at the end of the class.
        psql("-c", "CREATE EXTENSION IF NOT EXISTS pgcrypto");

        db.copyFileToContainer(MountableFile.forHostPath(SEED.toAbsolutePath()), "/tmp/demo-seed.sql");
        psql("-v", "demo_password=" + PASSWORD, "-f", "/tmp/demo-seed.sql");

        connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    @AfterAll
    static void stop() throws Exception {
        if (connection != null) {
            connection.close();
        }
        if (db != null) {
            db.stop();
        }
    }

    // ------------------------------------------------------------------ shape

    @Test
    @DisplayName("two colleges, because one tenant proves no isolation at all")
    void twoColleges() throws Exception {
        assertThat(column("SELECT code FROM colleges ORDER BY code"))
                .containsExactly("HIT", "NGC");
        assertThat(count("SELECT count(*) FROM students")).isEqualTo(EXPECTED_STUDENTS);
        assertThat(count("SELECT count(*) FROM students WHERE college_id = (SELECT id FROM colleges WHERE code = 'NGC')"))
                .as("the second college needs students of its own, or it cannot be used to prove anything")
                .isPositive();
    }

    @Test
    @DisplayName("every role a demo needs has somebody to sign in as")
    void everyRoleIsRepresented() throws Exception {
        Map<String, Long> byRole = new HashMap<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT r.name, count(*) FROM users u
                       JOIN user_roles ur ON ur.user_id = u.id
                       JOIN roles r ON r.id = ur.role_id
                      GROUP BY r.name
                     """)) {
            while (rs.next()) {
                byRole.put(rs.getString(1), rs.getLong(2));
            }
        }
        assertThat(byRole).containsKeys("SYSTEM_ADMIN", "COLLEGE_ADMIN", "TRAINER", "STUDENT");
        assertThat(byRole.get("SYSTEM_ADMIN")).isEqualTo(1);
        assertThat(byRole.get("STUDENT")).isEqualTo(EXPECTED_STUDENTS);

        assertThat(count("SELECT count(*) FROM users WHERE college_id IS NULL"))
                .as("a SYSTEM_ADMIN belongs to no college; one that does is scoped to it by the tenant filter")
                .isEqualTo(1);
    }

    // --------------------------------------------------------------- the point

    @Test
    @DisplayName("all but one student has skills, and that one is deliberate")
    void skillsArePresent() throws Exception {
        assertThat(count("SELECT count(DISTINCT student_id) FROM student_skills"))
                .as("before this seed existed, no local student had a single skill, "
                        + "so no skill-gap report had ever been produced outside a test")
                .isEqualTo(EXPECTED_STUDENTS - 1);

        assertThat(column("""
                SELECT roll_number FROM students s
                 WHERE NOT EXISTS (SELECT 1 FROM student_skills ss WHERE ss.student_id = s.id)
                """))
                .as("the student whose analysis comes back SKIPPED, so that path can be shown")
                .containsExactly("HIT2312");

        assertThat(count("SELECT count(*) FROM skills WHERE name IN ('SQL', 'Excel', 'Tableau')"))
                .as("the three skills the job corpus asks for most often; the catalogue shipped without them")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the profiles are uneven, so the reports are not all the same report")
    void profilesDiffer() throws Exception {
        long distinctSizes = count("""
                SELECT count(DISTINCT n) FROM (
                    SELECT count(*) AS n FROM student_skills GROUP BY student_id
                ) sizes
                """);
        assertThat(distinctSizes)
                .as("sixteen near-identical students would demonstrate the feature once, not sixteen times")
                .isGreaterThanOrEqualTo(3);
    }

    // ----------------------------------------------------------- the accounts

    @Test
    @DisplayName("every seeded account's password is readable by the encoder the application uses")
    void passwordsAreUsable() throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        List<String> hashes = column("SELECT password_hash FROM users ORDER BY email");

        assertThat(hashes).hasSize(count("SELECT count(*) FROM users").intValue());
        for (String hash : hashes) {
            // pgcrypto's crypt(.., gen_salt('bf')) and Spring's encoder have to
            // agree, or the seed produces a database nobody can sign in to --
            // which the seed itself cannot detect, and a demo finds out loudly.
            assertThat(encoder.matches(PASSWORD, hash))
                    .as("hash %s does not match the password the seed was given", hash.substring(0, 7))
                    .isTrue();
            assertThat(encoder.matches(PASSWORD + "-wrong", hash))
                    .as("any hash that accepts the wrong password accepts every password")
                    .isFalse();
        }

        // Exactly one, and on purpose. Everyone else is already onboarded -- a
        // demo that opens with twenty forced password changes is a demo of that
        // screen. The one is the student left in PENDING_SETUP, which is the
        // only state where "resend invitation" does anything but 409.
        assertThat(count("SELECT count(*) FROM users WHERE must_change_password"))
                .isEqualTo(1);
        assertThat(column("SELECT email FROM users WHERE account_status = 'PENDING_SETUP'"))
                .as("the invited-but-never-signed-in student, so the email path can be shown")
                .containsExactly("sneha@hillview.test");
    }

    // -------------------------------------------------------------- isolation

    @Test
    @DisplayName("nothing in the dataset crosses a college boundary")
    void nothingCrossesColleges() throws Exception {
        assertThat(count("""
                SELECT count(*) FROM enrollments e
                  JOIN students s ON s.id = e.student_id
                  JOIN batches b ON b.id = e.batch_id
                 WHERE s.college_id <> b.college_id OR e.college_id <> b.college_id
                """)).as("enrolments").isZero();

        assertThat(count("""
                SELECT count(*) FROM topic_progress tp
                  JOIN students s ON s.id = tp.student_id
                 WHERE s.college_id <> tp.college_id
                """)).as("progress rows").isZero();

        assertThat(count("""
                SELECT count(*) FROM placements p
                  JOIN students s ON s.id = p.student_id
                  JOIN companies c ON c.id = p.company_id
                 WHERE s.college_id <> c.college_id
                """)).as("placements").isZero();

        assertThat(count("""
                SELECT count(*) FROM feedback f
                  JOIN batches b ON b.id = f.batch_id
                  JOIN users fu ON fu.id = f.from_user_id
                  JOIN users tu ON tu.id = f.to_user_id
                 WHERE fu.college_id <> b.college_id OR tu.college_id <> b.college_id
                """)).as("feedback").isZero();

        // A cross-college row would not look like a bug on screen. It would look
        // like a Hillview admin who can see a Northgate student -- which is the
        // exact failure docs/SECURITY.md claims cannot happen, staged by the
        // fixture rather than caused by the code.
    }

    // --------------------------------------------------------------- rollups

    @Test
    @DisplayName("the progress rollup agrees with the progress it summarises")
    void theRollupIsConsistent() throws Exception {
        record Key(long student, long batch) { }
        Map<Key, List<String>> statuses = new HashMap<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT student_id, batch_id, status FROM topic_progress")) {
            while (rs.next()) {
                statuses.computeIfAbsent(new Key(rs.getLong(1), rs.getLong(2)), k -> new ArrayList<>())
                        .add(rs.getString(3));
            }
        }
        assertThat(statuses).as("there should be progress to summarise at all").isNotEmpty();

        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT student_id, batch_id, topics_total, topics_completed, weighted_percent
                       FROM student_batch_progress
                     """)) {
            int rows = 0;
            while (rs.next()) {
                rows++;
                Key key = new Key(rs.getLong(1), rs.getLong(2));
                List<String> theirs = statuses.get(key);
                assertThat(theirs).as("summary for %s with no progress rows behind it", key).isNotNull();

                assertThat(rs.getInt(3)).as("topics_total for %s", key).isEqualTo(theirs.size());
                assertThat(rs.getInt(4)).as("topics_completed for %s", key)
                        .isEqualTo((int) theirs.stream().filter("COMPLETED"::equals).count());

                // The weights come from ProgressStatus, so changing them there
                // and not here fails this test rather than leaving every seeded
                // percentage quietly wrong.
                double weighted = theirs.stream()
                        .mapToDouble(status -> ProgressStatus.valueOf(status).weight())
                        .sum();
                BigDecimal expected = BigDecimal.valueOf(weighted / theirs.size() * 100)
                        .setScale(2, RoundingMode.HALF_UP);
                assertThat(rs.getBigDecimal(5)).as("weighted_percent for %s", key)
                        .isEqualByComparingTo(expected);
            }
            assertThat(rows).as("one summary per (student, batch) with progress").isEqualTo(statuses.size());
        }
    }

    // ---------------------------------------------------------------- events

    @Test
    @DisplayName("one analysis is requested per student, in the envelope the application writes")
    void everyStudentIsQueuedForAnalysis() throws Exception {
        assertThat(count("SELECT count(*) FROM outbox_events"))
                .as("the reports are produced by the real pipeline, so every student needs an event")
                .isEqualTo(EXPECTED_STUDENTS);

        assertThat(column("SELECT DISTINCT event_type FROM outbox_events"))
                .containsExactly(EventType.PROFILE_UPDATED.name());
        assertThat(column("SELECT DISTINCT routing_key FROM outbox_events"))
                .containsExactly(RabbitMQConfig.PROFILE_UPDATED_KEY);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE schema_version <> "
                + EventType.PROFILE_UPDATED.schemaVersion()))
                .as("raising the event schema version has to fail here too, or the seed goes on "
                        + "writing a version nothing accepts")
                .isZero();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE status <> 'PENDING'"))
                .as("the relay has not run yet; anything else means the seed published it itself")
                .isZero();
    }

    @Test
    @DisplayName("each envelope deserialises into the record the relay publishes")
    void envelopesAreWellFormed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        int checked = 0;
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT o.event_id::text, o.aggregate_id, o.payload::text, s.college_id
                       FROM outbox_events o
                       JOIN students s ON s.id = o.aggregate_id::bigint
                     """)) {
            while (rs.next()) {
                checked++;
                EventEnvelope<Map<String, Object>> envelope =
                        mapper.readValue(rs.getString(3), EventEnvelope.class);

                // One id everywhere: the row, the envelope, and later the AMQP
                // message. A seed that let these drift would produce events the
                // consumer deduplicates against the wrong key.
                assertThat(envelope.eventId()).hasToString(rs.getString(1));
                assertThat(envelope.aggregateId()).isEqualTo(rs.getString(2));
                assertThat(envelope.aggregateType()).isEqualTo("Student");
                assertThat(envelope.collegeId()).isEqualTo(rs.getLong(4));
                assertThat(envelope.schemaVersion()).isEqualTo(EventType.PROFILE_UPDATED.schemaVersion());
                assertThat(envelope.payload()).containsEntry("studentId", Integer.parseInt(rs.getString(2)));
                assertThat(envelope.occurredAt()).endsWith("Z");
            }
        }
        assertThat(checked)
                .as("every event must name a student that exists, or the join above drops it")
                .isEqualTo(EXPECTED_STUDENTS);
    }

    // ------------------------------------------------------------ idempotence

    @Test
    @DisplayName("running it again gives the same database, and leaves the job corpus alone")
    void isRepeatable() throws Exception {
        // The corpus is the one thing in this database that cannot be rebuilt:
        // its source CSV is gone. The seed claims never to touch it, and a claim
        // about a destructive script is worth exactly as much as its test.
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    INSERT INTO industry_job_descriptions (title, company, raw_description, source)
                    VALUES ('Sentinel Row', 'DemoSeedTest', 'must survive a reseed', 'test')
                    """);
        }

        Map<String, Long> before = census();
        psql("-v", "demo_password=" + PASSWORD, "-f", "/tmp/demo-seed.sql");
        Map<String, Long> after = census();

        assertThat(after).as("a reset that does not settle is not a reset").isEqualTo(before);
        assertThat(count("SELECT count(*) FROM industry_job_descriptions WHERE company = 'DemoSeedTest'"))
                .as("the seed deleted a job description; nothing in it may write to that table")
                .isEqualTo(1);
    }

    private Map<String, Long> census() throws Exception {
        Map<String, Long> counts = new HashMap<>();
        for (String table : List.of("colleges", "users", "students", "student_skills", "trainers",
                "batches", "syllabus_topics", "enrollments", "enrollment_requests",
                "topic_progress", "student_batch_progress", "feedback", "placements",
                "companies", "outbox_events")) {
            counts.put(table, count("SELECT count(*) FROM " + table));
        }
        return counts;
    }

    // ------------------------------------------------------------------ util

    private static void psql(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "psql", "-U", db.getUsername(), "-d", db.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-q"));
        command.addAll(List.of(arguments));
        Container.ExecResult result = db.execInContainer(command.toArray(String[]::new));
        assertThat(result.getExitCode())
                .as("psql %s failed:%n%s%n%s", List.of(arguments), result.getStdout(), result.getStderr())
                .isZero();
    }

    private Long count(String sql) throws Exception {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String> column(String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
