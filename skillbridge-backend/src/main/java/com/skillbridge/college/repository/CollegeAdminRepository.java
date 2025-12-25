package com.skillbridge.college.repository;

import com.skillbridge.college.entity.CollegeAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CollegeAdminRepository extends JpaRepository<CollegeAdmin, Long> {
    List<CollegeAdmin> findByCollegeId(Long collegeId);
    Optional<CollegeAdmin> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
}

