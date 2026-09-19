package com.skillbridge.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds a self-contained college and tears it down again.
 *
 * <p>Written with JDBC rather than the repositories, for two reasons that have
 * both bitten this codebase. The entities carry {@code @SQLRestriction} and a
 * tenant filter, and a fixture that has to dodge the mechanisms under test is
 * not a fixture worth trusting. And a fixture built through the services would
 * emit its own queries into whatever is being measured.
 *
 * <p>Everything is scoped to a college code, so teardown is exact — a test that
 * leans on ambient data passes for reasons unrelated to the code. The suite
 * normally gets a fresh Testcontainers database, but it can still be pointed at
 * a shared one ({@code SKILLBRIDGE_TEST_DB=live}), where other rows exist.
 *
 * <p><b>Assign the field before calling {@link #seed}, not in one expression.</b>
 *
 * <pre>{@code
 * fixture = new TenantFixture(jdbc, CODE);   // not fixture = new ...seed(...)
 * fixture.seed(1, 3);
 * }</pre>
 *
 * <p>{@code seed} inserts as it goes, so a failure partway through leaves rows
 * behind. Chained into one expression the field is still null when that happens,
 * the {@code @AfterEach} throws {@code NullPointerException} instead of cleaning
 * up, and the rows stay. In a shared database that means leftover rows; it
 * happened against the live one, which had no staging copy. Observed on
 * 2026-09-10 when the link dropped during a fixture's own opening
 * {@code DELETE}.
 */
public class TenantFixture {

    private final JdbcTemplate jdbc;
    private final String collegeCode;

    public Long collegeId;
    public final List<Long> batchIds = new ArrayList<>();
    public final List<Long> studentIds = new ArrayList<>();
    public final List<Long> studentUserIds = new ArrayList<>();
    public Long trainerId;
    public Long trainerUserId;
    public Long adminUserId;

    public TenantFixture(JdbcTemplate jdbc, String collegeCode) {
        this.jdbc = jdbc;
        this.collegeCode = collegeCode;
    }

    /**
     * A college with {@code batches} batches and {@code students} students.
     *
     * <p>Every batch gets the trainer, a company, a two-level curriculum and
     * every student enrolled. That shape is the point: the associations are
     * exactly what an N+1 walks, so a fixture without them measures nothing.
     */
    public TenantFixture seed(int batches, int students) {
        return seed(batches, students, batches);
    }

    /**
     * @param modules how many curriculum modules each batch gets
     *
     * <p>Separate from {@code batches} because the syllabus endpoint returns a
     * tree, and a fixture that gives every tenant the same tree cannot see an
     * N+1 in it. The first version of this class did exactly that, and the
     * syllabus measurement looked flat when it was not.
     */
    public TenantFixture seed(int batches, int students, int modules) {
        remove();

        jdbc.update("""
                INSERT INTO colleges (name, code, email, phone, address, status, created_at, updated_at)
                VALUES (?, ?, ?, '0000000000', 'n/a', 'ACTIVE', now(), now())
                """, "Perf Fixture " + collegeCode, collegeCode, collegeCode.toLowerCase() + "@example.invalid");
        collegeId = jdbc.queryForObject("SELECT id FROM colleges WHERE code = ?", Long.class, collegeCode);

        adminUserId = insertUser("admin@" + host());
        trainerUserId = insertUser("trainer@" + host());
        trainerId = jdbc.queryForObject("""
                INSERT INTO trainers (user_id, college_id, full_name, phone, department,
                                      specialization, created_at, updated_at)
                VALUES (?, ?, 'Fixture Trainer', '0000000000', 'QA', 'Testing', now(), now())
                RETURNING id
                """, Long.class, trainerUserId, collegeId);

        Long companyId = jdbc.queryForObject("""
                INSERT INTO companies (college_id, name, domain, hiring_type, created_at, updated_at)
                VALUES (?, 'Fixture Corp', 'fixture.example', 'FULL_TIME', now(), now())
                RETURNING id
                """, Long.class, collegeId);

        for (int i = 0; i < students; i++) {
            Long userId = insertUser("student" + i + "@" + host());
            studentUserIds.add(userId);
            studentIds.add(jdbc.queryForObject("""
                    INSERT INTO students (user_id, college_id, full_name, roll_number, degree, branch,
                                          year, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'B.Tech', 'CSE', 3, now(), now())
                    RETURNING id
                    """, Long.class, userId, collegeId,
                    "Fixture Student " + i, collegeCode + "-" + i));
        }

        for (int b = 0; b < batches; b++) {
            Long batchId = jdbc.queryForObject("""
                    INSERT INTO batches (college_id, name, description, status, start_date, end_date,
                                         created_at, updated_at, version)
                    VALUES (?, ?, 'fixture batch', 'ACTIVE', CURRENT_DATE - ?, CURRENT_DATE + 30,
                            now(), now(), 0)
                    RETURNING id
                    """, Long.class, collegeId, "Fixture Batch " + b, b);
            batchIds.add(batchId);

            jdbc.update("INSERT INTO batch_trainers (batch_id, trainer_id, created_at) VALUES (?, ?, now())",
                    batchId, trainerId);
            jdbc.update("INSERT INTO batch_companies (batch_id, company_id, created_at) VALUES (?, ?, now())",
                    batchId, companyId);

            seedCurriculum(batchId, modules);

            // batchUpdate, not a loop of update(). The database is remote, so
            // the cost here is round trips, not rows: seeding 12 batches x 12
            // students one statement at a time took this fixture into minutes
            // and made the suite flaky, because every extra round trip is
            // another chance for the pooler to close the connection underneath
            // it.
            List<Object[]> enrollments = studentIds.stream()
                    .map(studentId -> new Object[]{batchId, studentId, collegeId})
                    .toList();
            jdbc.batchUpdate("""
                    INSERT INTO enrollments (batch_id, student_id, college_id, status, enrolled_at)
                    VALUES (?, ?, ?, 'ACTIVE', now())
                    """, enrollments);
        }
        return this;
    }

    /**
     * {@code modules} modules, two sub-modules each, two topics each.
     *
     * <p>The topics are collected across the whole curriculum and inserted in
     * one {@code batchUpdate}. Inserting them one at a time is where this
     * fixture used to spend most of its time.
     */
    private void seedCurriculum(Long batchId, int modules) {
        List<Object[]> topics = new ArrayList<>();
        for (int m = 0; m < modules; m++) {
            Long moduleId = jdbc.queryForObject("""
                    INSERT INTO syllabus_modules (batch_id, college_id, name, description, display_order,
                                                  created_at, updated_at)
                    VALUES (?, ?, ?, 'fixture module', ?, now(), now())
                    RETURNING id
                    """, Long.class, batchId, collegeId, "Module " + m, m);
            for (int s = 0; s < 2; s++) {
                Long submoduleId = jdbc.queryForObject("""
                        INSERT INTO syllabus_submodules (module_id, name, description, display_order,
                                                         created_at, updated_at)
                        VALUES (?, ?, 'fixture submodule', ?, now(), now())
                        RETURNING id
                        """, Long.class, moduleId, "Submodule " + s, s);
                for (int t = 0; t < 2; t++) {
                    topics.add(new Object[]{submoduleId, "Topic " + t, t});
                }
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO syllabus_topics (submodule_id, name, description, display_order,
                                             created_at, updated_at)
                VALUES (?, ?, 'fixture topic', ?, now(), now())
                """, topics);
    }

    private String host() {
        return collegeCode.toLowerCase() + ".example.invalid";
    }

    private Long insertUser(String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, college_id, is_active, created_at, updated_at)
                VALUES (?, 'not-a-real-hash', ?, true, now(), now())
                RETURNING id
                """, Long.class, email, collegeId);
    }

    /** Leaf-first, so no foreign key blocks the delete. */
    public void remove() {
        String colleges = "SELECT id FROM colleges WHERE code = '" + collegeCode + "'";
        String batches = "SELECT id FROM batches WHERE college_id IN (" + colleges + ")";
        String modules = "SELECT id FROM syllabus_modules WHERE batch_id IN (" + batches + ")";
        String submodules = "SELECT id FROM syllabus_submodules WHERE module_id IN (" + modules + ")";

        jdbc.update("DELETE FROM topic_progress WHERE college_id IN (" + colleges + ")");
        jdbc.update("DELETE FROM student_batch_progress WHERE college_id IN (" + colleges + ")");
        jdbc.update("DELETE FROM syllabus_topics WHERE submodule_id IN (" + submodules + ")");
        jdbc.update("DELETE FROM syllabus_submodules WHERE module_id IN (" + modules + ")");
        jdbc.update("DELETE FROM syllabus_modules WHERE batch_id IN (" + batches + ")");
        jdbc.update("DELETE FROM enrollments WHERE college_id IN (" + colleges + ")");
        jdbc.update("DELETE FROM enrollment_requests WHERE college_id IN (" + colleges + ")");
        jdbc.update("DELETE FROM feedback WHERE batch_id IN (" + batches + ")");
        jdbc.update("DELETE FROM batch_companies WHERE batch_id IN (" + batches + ")");
        jdbc.update("DELETE FROM batch_trainers WHERE batch_id IN (" + batches + ")");
        jdbc.update("DELETE FROM batches WHERE college_id IN (" + colleges + ")");
        jdbc.update("DELETE FROM companies WHERE college_id IN (" + colleges + ")");
        jdbc.update("DELETE FROM students WHERE college_id IN (" + colleges + ")");
        jdbc.update("DELETE FROM trainers WHERE college_id IN (" + colleges + ")");
        jdbc.update("DELETE FROM users WHERE college_id IN (" + colleges + ")");
        jdbc.update("DELETE FROM colleges WHERE code = ?", collegeCode);

        batchIds.clear();
        studentIds.clear();
        studentUserIds.clear();
    }
}
