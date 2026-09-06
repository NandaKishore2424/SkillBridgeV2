package com.skillbridge.common.audit;

import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.dto.PagedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'COLLEGE_ADMIN')")
    public ResponseEntity<PagedResponse<AuditLogDTO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action
    ) {
        // This table grows faster than any other; an unbounded page size is a
        // trivial way to make the server read millions of rows.
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        AuthenticatedUser caller = SecurityUtils.currentUser();

        // Tenant scope is decided first and the action filter applied within it.
        // Ordering these the other way round -- branching on the action filter
        // first -- would let ?action=... escape the college scope entirely.
        Page<AuditLog> rows;
        boolean filtered = action != null && !action.isBlank();
        if (caller.isSystemAdmin()) {
            rows = filtered
                    ? auditLogRepository.findByActionOrderByOccurredAtDesc(action, pageable)
                    : auditLogRepository.findAllByOrderByOccurredAtDesc(pageable);
        } else {
            Long collegeId = SecurityUtils.requireCollegeId();
            rows = filtered
                    ? auditLogRepository.findByCollegeIdAndActionOrderByOccurredAtDesc(collegeId, action, pageable)
                    : auditLogRepository.findByCollegeIdOrderByOccurredAtDesc(collegeId, pageable);
        }

        return ResponseEntity.ok(PagedResponse.<AuditLogDTO>builder()
                .items(rows.getContent().stream().map(AuditLogDTO::from).toList())
                .page(rows.getNumber())
                .size(rows.getSize())
                .totalElements(rows.getTotalElements())
                .totalPages(rows.getTotalPages())
                .build());
    }
}
