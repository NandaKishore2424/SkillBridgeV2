package com.skillbridge.syllabus.repository;

import com.skillbridge.syllabus.entity.SyllabusSubmodule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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
     * Initialises the {@code topics} collection of many sub-modules at once.
     *
     * <p>Used to read a whole curriculum tree in a fixed number of queries. The
     * obvious approach -- fetch-joining modules, sub-modules and topics in one
     * query -- is not available: both associations are {@code List}, and
     * Hibernate rejects two collection fetches in a single query with
     * {@code MultipleBagFetchException}.
     *
     * <p>So the tree is read in two: modules with their sub-modules, then this.
     * Because the sub-modules are already managed in the same persistence
     * context, this query initialises their collections <em>in place</em> --
     * the caller can then walk {@code submodule.getTopics()} freely and emit
     * nothing further. The returned list is deliberately ignored.
     *
     * <p>Without it the walk is one query per sub-module: a curriculum with 8
     * modules cost 18 statements where one with 2 cost 6.
     */
    @Query("SELECT DISTINCT s FROM SyllabusSubmodule s "
            + "LEFT JOIN FETCH s.topics "
            + "WHERE s.id IN :submoduleIds")
    List<SyllabusSubmodule> fetchTopicsFor(@Param("submoduleIds") Collection<Long> submoduleIds);

    /**
     * Check if a display order already exists for a module
     */
    boolean existsByModuleIdAndDisplayOrder(Long moduleId, Integer displayOrder);

    /**
     * Count sub-modules in a module
     */
    long countByModuleId(Long moduleId);
}
