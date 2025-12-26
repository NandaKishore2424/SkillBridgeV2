package com.skillbridge.bulkupload.repository;

import com.skillbridge.bulkupload.entity.BulkUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BulkUploadRepository extends JpaRepository<BulkUpload, Long> {
    List<BulkUpload> findByCollegeIdOrderByCreatedAtDesc(Long collegeId);

    List<BulkUpload> findByCollegeIdAndEntityTypeOrderByCreatedAtDesc(Long collegeId, String entityType);

    List<BulkUpload> findByStatus(String status);
}
