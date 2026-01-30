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
     * Delete all modules for a batch
     */
    void deleteByBatchId(Long batchId);
}
