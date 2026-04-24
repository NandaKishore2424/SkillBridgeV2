package com.skillbridge.enrollment.repository;

import com.skillbridge.enrollment.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    List<Enrollment> findByBatchId(Long batchId);

    Optional<Enrollment> findByBatchIdAndStudentId(Long batchId, Long studentId);

    int countByBatchId(Long batchId);

    int countByBatchIdIn(List<Long> batchIds);
}
