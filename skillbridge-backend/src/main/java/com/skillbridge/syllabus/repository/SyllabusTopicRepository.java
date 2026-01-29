package com.skillbridge.syllabus.repository;

import com.skillbridge.syllabus.entity.SyllabusTopic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for SyllabusTopic entities
 */
@Repository
public interface SyllabusTopicRepository extends JpaRepository<SyllabusTopic, Long> {

        /**
         * Find all topics for a module, ordered by display order
         */
        List<SyllabusTopic> findByModuleIdOrderByDisplayOrder(Long moduleId);

        /**
         * Find completed or incomplete topics for a module
         */
        List<SyllabusTopic> findByModuleIdAndIsCompleted(Long moduleId, Boolean isCompleted);

        /**
         * Check if a topic with the given order exists for a module
         */
        boolean existsByModuleIdAndDisplayOrder(Long moduleId, Integer displayOrder);

        /**
         * Find a topic by module ID and display order
         */
        Optional<SyllabusTopic> findByModuleIdAndDisplayOrder(Long moduleId, Integer displayOrder);

        /**
         * Count total topics for a module
         */
        long countByModuleId(Long moduleId);

        /**
         * Count completed topics for a module
         */
        long countByModuleIdAndIsCompleted(Long moduleId, Boolean isCompleted);

        /**
         * Get completion statistics for a batch
         */
        @Query("SELECT COUNT(t) FROM SyllabusTopic t " +
                        "JOIN t.module m " +
                        "WHERE m.batch.id = :batchId AND t.isCompleted = true")
        long countCompletedTopicsByBatchId(@Param("batchId") Long batchId);

        /**
         * Count total topics for a batch
         */
        @Query("SELECT COUNT(t) FROM SyllabusTopic t " +
                        "JOIN t.module m " +
                        "WHERE m.batch.id = :batchId")
        long countTotalTopicsByBatchId(@Param("batchId") Long batchId);

        /**
         * Delete all topics for a module
         */
        void deleteByModuleId(Long moduleId);
}
