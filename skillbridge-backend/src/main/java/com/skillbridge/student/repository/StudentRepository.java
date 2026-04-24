package com.skillbridge.student.repository;

import com.skillbridge.student.entity.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findByUser_Id(Long userId);

    List<Student> findByCollegeId(Long collegeId);
    Page<Student> findByCollegeId(Long collegeId, Pageable pageable);

    Optional<Student> findByRollNumberAndCollegeId(String rollNumber, Long collegeId);

    boolean existsByRollNumberAndCollegeId(String rollNumber, Long collegeId);

    boolean existsByUser_Id(Long userId);

    long countByCollegeId(Long collegeId);
}
