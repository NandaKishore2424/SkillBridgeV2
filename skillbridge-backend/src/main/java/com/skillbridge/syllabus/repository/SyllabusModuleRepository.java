package com.skillbridge.syllabus.repository;

import com.skillbridge.syllabus.entity.SyllabusModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for SyllabusModule entities
 */
@Repository
public interface SyllabusModuleRepository extends JpaRepository<SyllabusModule, Long> {

    /**
     * Find all modules for a batch, ordered by display order
     */
    List<SyllabusModule> findByBatchIdOrderByDisplayOrder(Long batchId);

    /**
     * Check if a module with the given order exists for a batch
     */
    boolean existsByBatchIdAndDisplayOrder(Long batchId, Integer displayOrder);

    /**
     * Find a module by batch ID and display order
     */
    Optional<SyllabusModule> findByBatchIdAndDisplayOrder(Long batchId, Integer displayOrder);

    /**
     * Count modules for a batch
     */
    long countByBatchId(Long batchId);

    /**
     * Find all modules for a batch with their submodules fetched
     * Topics will be loaded lazily when accessed
     */
    @Query("SELECT DISTINCT m FROM SyllabusModule m " +
            "LEFT JOIN FETCH m.submodules " +
            "WHERE m.batch.id = :batchId " +
            "ORDER BY m.displayOrder")
    List<SyllabusModule> findByBatchIdWithSubmodulesAndTopics(@Param("batchId") Long batchId);

    /**
     * Module names for a set of batches, as {@code [batchId, name]} rows.
     *
     * <p>Feeds the recommendation engine's keyword extraction. Returns a
     * projection rather than entities because the caller wants two scalars per
     * row and has no use for a managed {@code SyllabusModule} — hydrating dozens
     * of entities to read one string off each is pure overhead.
     */
    @Query("SELECT m.batch.id, m.name FROM SyllabusModule m WHERE m.batch.id IN :batchIds")
    List<Object[]> findModuleNamesByBatchIds(@Param("batchIds") List<Long> batchIds);

    /**
     * Delete all modules for a batch
     */
    void deleteByBatchId(Long batchId);
}
