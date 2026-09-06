package com.skillbridge.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByCollegeIdOrderByOccurredAtDesc(Long collegeId, Pageable pageable);

    Page<AuditLog> findByActorUserIdOrderByOccurredAtDesc(Long actorUserId, Pageable pageable);

    /** SYSTEM_ADMIN only — unscoped across every college. */
    Page<AuditLog> findByActionOrderByOccurredAtDesc(String action, Pageable pageable);

    /**
     * The tenant-scoped form of the above.
     *
     * <p>Both exist because filtering by action must not become a way around
     * the college scope: without this, a COLLEGE_ADMIN asking for
     * {@code ?action=LOGIN_FAILURE} would have been served every college's rows.
     */
    Page<AuditLog> findByCollegeIdAndActionOrderByOccurredAtDesc(Long collegeId, String action, Pageable pageable);

    Page<AuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);
}
