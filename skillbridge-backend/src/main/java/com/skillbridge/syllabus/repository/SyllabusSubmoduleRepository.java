package com.skillbridge.syllabus.repository;

import com.skillbridge.syllabus.entity.SyllabusSubmodule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for SyllabusSubmodule entity
 */
@Repository
public interface SyllabusSubmoduleRepository extends JpaRepository<SyllabusSubmodule, Long> {

    /**
     * Find all sub-modules for a given module with their topics eagerly loaded
     */
    @Query("SELECT DISTINCT s FROM SyllabusSubmodule s " +
            "LEFT JOIN FETCH s.topics " +
            "WHERE s.module.id = :moduleId " +
            "ORDER BY s.displayOrder")
    List<SyllabusSubmodule> findByModuleIdWithTopics(@Param("moduleId") Long moduleId);

    /**
     * Check if a display order already exists for a module
     */
    boolean existsByModuleIdAndDisplayOrder(Long moduleId, Integer displayOrder);

    /**
     * Count sub-modules in a module
     */
    long countByModuleId(Long moduleId);
}
