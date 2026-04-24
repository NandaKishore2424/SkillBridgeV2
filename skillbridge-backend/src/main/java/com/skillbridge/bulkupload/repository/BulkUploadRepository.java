package com.skillbridge.bulkupload.repository;

import com.skillbridge.bulkupload.entity.BulkUpload;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BulkUploadRepository extends JpaRepository<BulkUpload, Long> {
    List<BulkUpload> findByCollegeIdOrderByCreatedAtDesc(Long collegeId);

    List<BulkUpload> findByCollegeIdAndEntityTypeOrderByCreatedAtDesc(Long collegeId, String entityType);

    Page<BulkUpload> findByCollegeIdOrderByCreatedAtDesc(Long collegeId, Pageable pageable);
    Page<BulkUpload> findByCollegeIdAndEntityTypeOrderByCreatedAtDesc(Long collegeId, String entityType, Pageable pageable);
    List<BulkUpload> findByStatus(String status);
}
