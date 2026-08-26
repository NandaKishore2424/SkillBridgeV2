package com.skillbridge.batch.repository;

import com.skillbridge.batch.entity.Batch;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BatchRepository extends JpaRepository<Batch, Long> {

    List<Batch> findByCollegeId(Long collegeId);

    Page<Batch> findByCollegeId(Long collegeId, Pageable pageable);

    long countByCollegeId(Long collegeId);

    long countByCollegeIdAndStatus(Long collegeId, String status);

    // --- Soft-delete aware reads -------------------------------------------
    // Batches are never hard deleted, so anything user-facing has to exclude
    // deleted rows explicitly. Keeping these alongside the unfiltered finders
    // rather than replacing them, because admin and reporting paths legitimately
    // want to see removed batches.

    List<Batch> findByCollegeIdAndDeletedAtIsNull(Long collegeId);

    Optional<Batch> findByIdAndDeletedAtIsNull(Long id);

    /**
     * Batches a student could apply to: open in their college, not already
     * applied for or enrolled in.
     *
     * <p>{@code :excludedIds} arrives from the caller as a list that is never
     * empty — an empty {@code IN ()} is a syntax error in some drivers, so the
     * caller substitutes a sentinel rather than this query guarding for it.
     */
    @Query("""
           SELECT b FROM Batch b
           WHERE b.college.id = :collegeId
             AND b.deletedAt IS NULL
             AND b.status IN ('OPEN', 'UPCOMING')
             AND b.id NOT IN :excludedIds
           ORDER BY b.startDate ASC
           """)
    List<Batch> findOpenForCollegeExcluding(@Param("collegeId") Long collegeId,
                                            @Param("excludedIds") Collection<Long> excludedIds);

    @Query("""
           SELECT b FROM Batch b
           WHERE b.college.id = :collegeId
             AND b.deletedAt IS NULL
             AND b.status IN ('OPEN', 'ACTIVE', 'UPCOMING')
           ORDER BY b.startDate ASC
           """)
    List<Batch> findAvailableForCollege(@Param("collegeId") Long collegeId);

    /**
     * Load a batch for a capacity-sensitive write.
     *
     * <p>Takes a row lock so that concurrent applications to the same batch
     * serialise on it. Without this the capacity check is a read-then-write race:
     * two applicants both see one seat free and both take it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Batch b WHERE b.id = :id AND b.deletedAt IS NULL")
    Optional<Batch> findByIdForUpdate(@Param("id") Long id);
}
