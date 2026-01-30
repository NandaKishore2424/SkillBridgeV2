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
         * Find all topics for a sub-module, ordered by display order
         */
        List<SyllabusTopic> findBySubmoduleIdOrderByDisplayOrder(Long submoduleId);

        /**
         * Find completed or incomplete topics for a sub-module
         */
        List<SyllabusTopic> findBySubmoduleIdAndIsCompleted(Long submoduleId, Boolean isCompleted);

        /**
         * Check if a topic with the given order exists for a sub-module
         */
        boolean existsBySubmoduleIdAndDisplayOrder(Long submoduleId, Integer displayOrder);

        /**
         * Find a topic by sub-module ID and display order
         */
        Optional<SyllabusTopic> findBySubmoduleIdAndDisplayOrder(Long submoduleId, Integer displayOrder);

        /**
         * Count total topics for a sub-module
         */
        long countBySubmoduleId(Long submoduleId);

        /**
         * Count completed topics for a sub-module
         */
        long countBySubmoduleIdAndIsCompleted(Long submoduleId, Boolean isCompleted);

        /**
         * Get completion statistics for a batch
         */
        @Query("SELECT COUNT(t) FROM SyllabusTopic t " +
                        "JOIN t.submodule s " +
                        "JOIN s.module m " +
                        "WHERE m.batch.id = :batchId AND t.isCompleted = true")
        long countCompletedTopicsByBatchId(@Param("batchId") Long batchId);

        /**
         * Count total topics for a batch
         */
        @Query("SELECT COUNT(t) FROM SyllabusTopic t " +
                        "JOIN t.submodule s " +
                        "JOIN s.module m " +
                        "WHERE m.batch.id = :batchId")
        long countTotalTopicsByBatchId(@Param("batchId") Long batchId);

        /**
         * Delete all topics for a sub-module
         */
        void deleteBySubmoduleId(Long submoduleId);
}
