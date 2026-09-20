package com.skillbridge.bulkupload;

import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Deleting an account does not delete, or block, the uploads it made.
 *
 * <p>{@code uploaded_by_user_id} was NOT NULL while its foreign key said
 * ON DELETE SET NULL. Both cannot hold: the delete failed on the constraint,
 * and every test that removed a user had to delete its uploads first. V9 drops
 * the NOT NULL, so the history survives with no uploader.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@IntegrationTest
class UploadOutlivesUploaderTest {

    @Autowired private JdbcTemplate jdbc;

    private TenantFixture fixture;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, "UPLOADKEEP");
        fixture.seed(0, 0);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM bulk_uploads WHERE college_id = ?", fixture.collegeId);
        fixture.remove();
    }

    @Test
    @DisplayName("the upload keeps its history when its uploader is deleted")
    void uploadSurvives() {
        Long uploadId = jdbc.queryForObject("""
                INSERT INTO bulk_uploads (college_id, uploaded_by_user_id, entity_type, file_name,
                                          total_rows, successful_rows, failed_rows, status)
                VALUES (?, ?, 'STUDENT', 'students.csv', 3, 3, 0, 'COMPLETED') RETURNING id
                """, Long.class, fixture.collegeId, fixture.adminUserId);

        assertThatNoException()
                .as("deleting the uploader used to fail on uploaded_by_user_id NOT NULL")
                .isThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", fixture.adminUserId));

        assertThat(jdbc.queryForMap("SELECT file_name, total_rows, uploaded_by_user_id FROM bulk_uploads WHERE id = ?",
                uploadId))
                .containsEntry("file_name", "students.csv")
                .containsEntry("total_rows", 3)
                .containsEntry("uploaded_by_user_id", null);
    }
}
