package com.skillbridge.progress.repository;

import com.skillbridge.progress.entity.StudentBatchProgress;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudentBatchProgressRepository extends JpaRepository<StudentBatchProgress, Long> {

    Optional<StudentBatchProgress> findByStudentIdAndBatchId(Long studentId, Long batchId);

    List<StudentBatchProgress> findByStudentId(Long studentId);

    /**
     * Summaries for one student across a set of batches — one query for a whole
     * dashboard, rather than one per enrolled batch.
     */
    List<StudentBatchProgress> findByStudentIdAndBatchIdIn(Long studentId, Collection<Long> batchIds);

    Page<StudentBatchProgress> findByBatchIdOrderByWeightedPercentAsc(Long batchId, Pageable pageable);

    /**
     * Students falling behind in a batch.
     *
     * <p>Ordered ascending so the ones most in need of attention come first,
     * which is the only ordering that makes the report worth opening.
     */
    @Query(value = """
           SELECT sbp FROM StudentBatchProgress sbp
           WHERE sbp.batchId = :batchId
             AND sbp.weightedPercent < :threshold
           ORDER BY sbp.weightedPercent ASC
           """,
           countQuery = """
           SELECT count(sbp) FROM StudentBatchProgress sbp
           WHERE sbp.batchId = :batchId
             AND sbp.weightedPercent < :threshold
           """)
    Page<StudentBatchProgress> findAtRisk(@Param("batchId") Long batchId,
                                          @Param("threshold") java.math.BigDecimal threshold,
                                          Pageable pageable);
}
