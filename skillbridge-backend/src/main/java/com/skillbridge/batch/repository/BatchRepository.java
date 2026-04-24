package com.skillbridge.batch.repository;

import com.skillbridge.batch.entity.Batch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BatchRepository extends JpaRepository<Batch, Long> {
    List<Batch> findByCollegeId(Long collegeId);
    Page<Batch> findByCollegeId(Long collegeId, Pageable pageable);

    long countByCollegeId(Long collegeId);

    long countByCollegeIdAndStatus(Long collegeId, String status);
}
