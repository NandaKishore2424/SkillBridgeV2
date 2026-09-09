package com.skillbridge.search;

import com.skillbridge.common.dto.Pagination;
import com.skillbridge.student.dto.StudentDTO;
import com.skillbridge.student.service.StudentService;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code search_text} must stay true, or the search box quietly stops finding
 * people.
 *
 * <p>The admin student and trainer searches used to OR five columns, one of
 * which — email — lives on {@code users}. A disjunction spanning two relations
 * cannot be pushed to either side, so no index could ever serve it. The fix
 * replaced it with one generated column and one GIN index, which made the same
 * searches 33–87× faster on a 50,000-row copy of this schema.
 *
 * <p>That fix moved a correctness risk into the database. {@code search_text}
 * is {@code GENERATED ALWAYS}, so Postgres itself keeps it true for the four
 * columns on the row — but email is not on the row. It is denormalised onto
 * {@code students.user_email} by one trigger and kept current by a second,
 * {@code trg_users_email_propagate}. Neither trigger is visible from Java, and
 * neither would fail loudly: if the propagate trigger were dropped, every test
 * that searches by a freshly-seeded email would still pass, and the only
 * symptom in production would be that searching for someone's <em>current</em>
 * address finds nothing while their old one still works.
 *
 * <p>So the assertions here are deliberately about staleness, not about
 * matching: each one changes something after the row was written and then
 * searches for the new value.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+",
        disabledReason = "needs a PostgreSQL instance; set DATABASE_URL to run")
class SearchTextMaintenanceTest {

    private static final String COLLEGE_CODE = "SEARCHTEXT";

    @Autowired
    private StudentService studentService;

    @Autowired
    private JdbcTemplate jdbc;

    private TenantFixture fixture;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, COLLEGE_CODE).seed(1, 3);
    }

    @AfterEach
    void cleanUp() {
        fixture.remove();
    }

    private List<String> searchNames(String term) {
        Page<StudentDTO> page = studentService.getStudentsByCollege(
                fixture.collegeId, term, null,
                Pagination.of(0, 50, Sort.by(Sort.Direction.ASC, "fullName")));
        return page.getContent().stream().map(StudentDTO::getFullName).toList();
    }

    @Test
    @DisplayName("the insert trigger populates search_text for a row written without it")
    void populatedOnInsert() {
        // TenantFixture inserts students with raw SQL that never mentions
        // user_email, which is exactly how the application inserts them too.
        List<String> texts = jdbc.queryForList(
                "SELECT search_text FROM students WHERE college_id = ? ORDER BY id",
                String.class, fixture.collegeId);

        assertThat(texts).hasSize(3);
        assertThat(texts).allSatisfy(t -> assertThat(t)
                .as("search_text should carry the row's own text and the login's email")
                .contains("fixture student")
                .contains("@" + COLLEGE_CODE.toLowerCase() + ".example.invalid"));
    }

    @Test
    @DisplayName("searching by email still finds the student — the capability the rewrite had to keep")
    void findableByEmail() {
        String email = jdbc.queryForObject(
                "SELECT u.email FROM users u JOIN students s ON s.user_id = u.id WHERE s.id = ?",
                String.class, fixture.studentIds.get(0));

        assertThat(searchNames(email))
                .as("email is not a column on students; if this fails the denormalisation is gone")
                .containsExactly("Fixture Student 0");
    }

    @Test
    @DisplayName("renaming a student makes the new name findable — the generated column")
    void followsARename() {
        Long id = fixture.studentIds.get(1);
        jdbc.update("UPDATE students SET full_name = 'Renamed Persona' WHERE id = ?", id);

        assertThat(searchNames("Renamed Persona")).containsExactly("Renamed Persona");
        assertThat(searchNames("Fixture Student 1"))
                .as("the old name must stop matching, or search_text is stale")
                .isEmpty();
    }

    @Test
    @DisplayName("changing a login's email makes the new address findable and the old one not")
    void followsAnEmailChange() {
        Long studentId = fixture.studentIds.get(2);
        Long userId = jdbc.queryForObject(
                "SELECT user_id FROM students WHERE id = ?", Long.class, studentId);
        String oldEmail = jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, userId);
        String newEmail = "moved.address@" + COLLEGE_CODE.toLowerCase() + ".example.invalid";

        jdbc.update("UPDATE users SET email = ? WHERE id = ?", newEmail, userId);

        // This is the assertion that fails if trg_users_email_propagate is
        // dropped: without it user_email keeps the old address forever.
        assertThat(searchNames(newEmail))
                .as("the current address must be searchable")
                .containsExactly("Fixture Student 2");
        assertThat(searchNames(oldEmail))
                .as("the old address must stop matching, or search_text is stale")
                .isEmpty();
    }

    @Test
    @DisplayName("search is case-insensitive, because the column is stored lowercased")
    void caseInsensitive() {
        assertThat(searchNames("FIXTURE STUDENT 0")).containsExactly("Fixture Student 0");
        assertThat(searchNames("fixture student 0")).containsExactly("Fixture Student 0");
    }

    @Test
    @DisplayName("a blank term is not a filter")
    void blankTermReturnsEveryone() {
        assertThat(searchNames("")).hasSize(3);
        assertThat(searchNames(null)).hasSize(3);
    }
}
