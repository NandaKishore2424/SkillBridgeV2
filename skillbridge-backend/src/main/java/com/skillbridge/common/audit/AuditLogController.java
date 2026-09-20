package com.skillbridge.common.audit;

import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Cursor;
import com.skillbridge.common.dto.CursorPage;
import java.time.LocalDateTime;
import java.util.List;
import com.skillbridge.common.dto.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import com.skillbridge.common.security.CollegeOrSystemAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to the audit trail.
 *
 * <p>Scoping is done here rather than by the Hibernate {@code collegeFilter},
 * because {@link AuditLog} carries a plain {@code collegeId} column and no
 * {@code @Filter} — and, as the tenancy audit established, a filter would not
 * be a substitute for an explicit check anyway. A COLLEGE_ADMIN is served by a
 * repository method that takes their own college id, so there is no id from the
 * request to get wrong.
 *
 * <p>There is no write endpoint, and no update or delete anywhere: the table is
 * append-only. An audit trail an administrator can edit is not evidence of
 * anything.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditLogRepository auditLogRepository;

    /**
     * The audit trail, newest first, keyset-paginated.
     *
     * <p>Pass no {@code cursor} for the first page, then the {@code nextCursor}
     * from each response for the one after. There is no page number and no
     * total: this table grows faster than any other here, and both a
     * {@code COUNT(*)} and an {@code OFFSET} get more expensive the deeper the
     * trail goes, while a seek does not.
     */
    @GetMapping
    @CollegeOrSystemAdmin
    public ResponseEntity<CursorPage<AuditLogDTO>> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action
    ) {
        // Blank cursor decodes to Cursor.start(), which is why there is no
        // first-page branch anywhere below.
        Cursor from = Cursor.decode(cursor);
        LocalDateTime cursorTime = from.timestamp();
        Long cursorId = from.id();

        // One row more than asked for, so "is there another page" costs a row
        // rather than a COUNT. Pagination.of still clamps the size, so the
        // extra row cannot be used to step past the cap.
        int limit = Pagination.clampSize(size);
        PageRequest window = Pagination.of(0, limit + 1);

        AuthenticatedUser caller = SecurityUtils.currentUser();

        // Tenant scope is decided first and the action filter applied within it.
        // Ordering these the other way round -- branching on the action filter
        // first -- would let ?action=... escape the college scope entirely.
        List<AuditLog> rows;
        boolean filtered = action != null && !action.isBlank();
        if (caller.isSystemAdmin()) {
            rows = filtered
                    ? auditLogRepository.seekByAction(action, cursorTime, cursorId, window)
                    : auditLogRepository.seekAll(cursorTime, cursorId, window);
        } else {
            Long collegeId = SecurityUtils.requireCollegeId();
            rows = filtered
                    ? auditLogRepository.seekByCollegeAndAction(collegeId, action, cursorTime, cursorId, window)
                    : auditLogRepository.seekByCollege(collegeId, cursorTime, cursorId, window);
        }

        return ResponseEntity.ok(CursorPage.of(rows, limit, AuditLogDTO::from,
                row -> new Cursor(row.getOccurredAt(), row.getId()).encode()));
    }
}
