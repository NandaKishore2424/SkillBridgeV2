package com.skillbridge.bulkupload.repository;

import com.skillbridge.bulkupload.entity.BulkUploadResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;

@Repository
public interface BulkUploadResultRepository extends JpaRepository<BulkUploadResult, Long> {

    Page<BulkUploadResult> findByBulkUploadIdAndStatusInOrderByRowNumber(Long bulkUploadId,
                                                                         Collection<String> statuses,
                                                                         Pageable pageable);

    long countByBulkUploadIdAndStatus(Long bulkUploadId, String status);
}
