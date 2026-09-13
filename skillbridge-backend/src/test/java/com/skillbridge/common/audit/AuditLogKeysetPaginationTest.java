package com.skillbridge.common.audit;

import com.skillbridge.common.dto.Cursor;
import com.skillbridge.common.dto.CursorPage;
import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the audit log's keyset seek returns every row exactly once, including
 * when rows share a timestamp.
 *
 * <p>The tie-break is the whole reason the cursor is composite, and the live
 * data cannot test it: at the time of writing, all 100 audit rows in the
 * database had distinct {@code occurred_at} values, so walking them by cursor
 * exercised nothing. A test that only ever sees distinct timestamps would pass
 * against a cursor with no tie-break at all — which is the failure this
 * codebase has already shipped twice.
 *
 * <p>So the fixture writes rows that deliberately collide: fifteen sharing one
 * timestamp, straddling several page boundaries. That is not a contrived
 * scenario. One HTTP request that audits several actions stamps them within the
 * same {@code LocalDateTime}, so ties are the normal case in an audit trail,
 * not the edge.
 */
@SpringBootTest
@IntegrationTest
class AuditLogKeysetPaginationTest {

    /** Marks the fixture rows so teardown never touches real audit history. */
    private static final String FIXTURE_ACTION = "KEYSET_PAGINATION_TEST";

    /** The fixture's own college, so the seek has a tenant that exists. */
    private static final String FIXTURE_COLLEGE_CODE = "KEYSETPAGTEST";

    /** Well past any real row, so the fixture is always the newest thing. */
    private static final LocalDateTime TIED_AT = LocalDateTime.of(2099, 1, 1, 12, 0, 0);

    private static final int TIED_ROWS = 15;
    private static final int PAGE_SIZE = 4;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Long collegeId;

    @BeforeEach
    void seed() {
        removeFixture();
        collegeId = seedCollege();

        // All fifteen share one timestamp. With PAGE_SIZE 4 they span four page
        // boundaries, so a cursor that cannot break the tie has four chances to
        // skip or repeat.
        for (int i = 0; i < TIED_ROWS; i++) {
            jdbc.update("""
                    INSERT INTO audit_log (occurred_at, action, outcome, college_id, actor_email)
                    VALUES (?, ?, 'SUCCESS', ?, ?)
                    """, TIED_AT, FIXTURE_ACTION, collegeId, "keyset-" + i + "@example.invalid");
        }
    }

    @AfterEach
    void tearDown() {
        removeFixture();
    }

    private void removeFixture() {
        jdbc.update("DELETE FROM audit_log WHERE action = ?", FIXTURE_ACTION);
        jdbc.update("DELETE FROM colleges WHERE code = ?", FIXTURE_COLLEGE_CODE);
    }

    /**
     * The fixture's own college.
     *
     * <p>This used to be {@code SELECT min(id) FROM colleges} — borrowing
     * whichever tenant happened to be first. Against live that always resolved
     * to something; against an empty database it resolves to {@code NULL}, the
     * fifteen rows are written with a null {@code college_id}, and
     * {@code seekByCollegeAndAction} matches none of them. The test then fails
     * with "Expected size: 15 but was: 0", which reads like a broken cursor and
     * is really a missing tenant.
     *
     * <p>Seeding its own is both the fix and the stronger test: the rows now
     * belong to a college that contains nothing else, so the seek is walking the
     * fixture and only the fixture.
     */
    private Long seedCollege() {
        jdbc.update("""
                INSERT INTO colleges (name, code, email, phone, address, status, created_at, updated_at)
                VALUES ('Keyset Pagination Test College', ?, 'keyset@example.invalid',
                        '0000000000', 'n/a', 'ACTIVE', now(), now())
                """, FIXTURE_COLLEGE_CODE);
        return jdbc.queryForObject(
                "SELECT id FROM colleges WHERE code = ?", Long.class, FIXTURE_COLLEGE_CODE);
    }

    @Test
    @DisplayName("rows sharing a timestamp are each returned exactly once")
    void tiedTimestampsAreNeitherSkippedNorRepeated() {
        List<Long> walked = walkByCursor();

        assertThat(walked)
                .as("every seeded row must appear")
                .hasSize(TIED_ROWS);
        assertThat(walked)
                .as("and none of them twice")
                .doesNotHaveDuplicates();
        assertThat(walked)
                .as("ordered by id descending within the tie, which is what makes the seek exact")
                .isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    @DisplayName("the first page needs no cursor, and the last reports hasMore=false")
    void cursorLifecycle() {
        CursorPage<Long> first = page(Cursor.start());

        assertThat(first.getItems()).hasSize(PAGE_SIZE);
        assertThat(first.isHasMore()).isTrue();
        assertThat(first.getNextCursor()).isNotBlank();

        CursorPage<Long> last = page(Cursor.decode(walkToLastCursor()));
        assertThat(last.isHasMore()).isFalse();
        assertThat(last.getNextCursor()).isNull();
    }

    @Test
    @DisplayName("a cursor survives a round trip through its encoding")
    void cursorRoundTrips() {
        Cursor original = new Cursor(TIED_AT, 4242L);
        Cursor decoded = Cursor.decode(original.encode());

        assertThat(decoded).isEqualTo(original);
        // Opaque to the client: the encoding must not read as its contents.
        assertThat(original.encode()).doesNotContain("2099").doesNotContain("4242");
    }

    // ------------------------------------------------------------------

    /** Walks the fixture page by page, collecting ids in order. */
    private List<Long> walkByCursor() {
        List<Long> ids = new ArrayList<>();
        Cursor cursor = Cursor.start();
        for (int guard = 0; guard < 100; guard++) {
            CursorPage<Long> page = page(cursor);
            ids.addAll(page.getItems());
            if (!page.isHasMore()) {
                return ids;
            }
            cursor = Cursor.decode(page.getNextCursor());
        }
        throw new IllegalStateException("cursor walk did not terminate");
    }

    private String walkToLastCursor() {
        Cursor cursor = Cursor.start();
        String last = null;
        for (int guard = 0; guard < 100; guard++) {
            CursorPage<Long> page = page(cursor);
            if (!page.isHasMore()) {
                return last;
            }
            last = page.getNextCursor();
            cursor = Cursor.decode(last);
        }
        throw new IllegalStateException("cursor walk did not terminate");
    }

    /**
     * One page of the fixture, through the same finder the controller uses.
     *
     * <p>Filtered by the fixture's action so the assertions are about seeded
     * rows only — the table has real history in it, and a test that depended on
     * that history would pass or fail for reasons unrelated to the code.
     */
    private CursorPage<Long> page(Cursor cursor) {
        List<AuditLog> rows = auditLogRepository.seekByCollegeAndAction(
                collegeId, FIXTURE_ACTION, cursor.timestamp(), cursor.id(),
                PageRequest.of(0, PAGE_SIZE + 1));

        return CursorPage.of(rows, PAGE_SIZE, AuditLog::getId,
                row -> new Cursor(row.getOccurredAt(), row.getId()).encode());
    }
}
