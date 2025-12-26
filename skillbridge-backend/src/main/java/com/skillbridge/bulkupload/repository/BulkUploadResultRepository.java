package com.skillbridge.bulkupload.repository;

import com.skillbridge.bulkupload.entity.BulkUploadResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BulkUploadResultRepository extends JpaRepository<BulkUploadResult, Long> {
    List<BulkUploadResult> findByBulkUploadId(Long bulkUploadId);

    List<BulkUploadResult> findByBulkUploadIdAndStatus(Long bulkUploadId, String status);

    long countByBulkUploadIdAndStatus(Long bulkUploadId, String status);
}
