package com.skillbridge.college.repository;

import com.skillbridge.college.entity.College;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CollegeRepository extends JpaRepository<College, Long> {
    Optional<College> findByCode(String code);

    /**
     * Colleges in one status, a page at a time.
     *
     * <p>The public {@code /colleges/active} endpoint used to call
     * {@code findAll()} and filter the result in memory, so an unauthenticated
     * request read every college row into the heap and then threw most of them
     * away. The predicate belongs in SQL.
     */
    Page<College> findByStatus(String status, Pageable pageable);
}

