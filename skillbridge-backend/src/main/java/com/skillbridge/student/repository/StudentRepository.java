package com.skillbridge.student.repository;

import com.skillbridge.student.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findByUserId(Long userId);

    List<Student> findByCollegeId(Long collegeId);

    Optional<Student> findByRollNumberAndCollegeId(String rollNumber, Long collegeId);

    boolean existsByRollNumberAndCollegeId(String rollNumber, Long collegeId);

    boolean existsByUserId(Long userId);

    long countByCollegeId(Long collegeId);
}
