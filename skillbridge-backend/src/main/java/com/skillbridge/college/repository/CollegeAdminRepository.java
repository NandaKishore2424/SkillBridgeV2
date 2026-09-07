package com.skillbridge.college.repository;

import com.skillbridge.college.entity.CollegeAdmin;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CollegeAdminRepository extends JpaRepository<CollegeAdmin, Long> {
    List<CollegeAdmin> findByCollegeId(Long collegeId);

    /**
     * Admins of one college, a page at a time, with the user row fetched.
     *
     * <p>The response carries each admin's email, which lives on {@code user};
     * without the fetch that is one extra select per admin.
     */
    @Query(value = "select a from CollegeAdmin a join fetch a.user where a.college.id = :collegeId",
           countQuery = "select count(a) from CollegeAdmin a where a.college.id = :collegeId")
    Page<CollegeAdmin> findByCollege(@Param("collegeId") Long collegeId, Pageable pageable);
    Optional<CollegeAdmin> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
}

