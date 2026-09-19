package com.skillbridge.bulkupload.repository;

import com.skillbridge.bulkupload.entity.BulkUpload;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface BulkUploadRepository extends JpaRepository<BulkUpload, Long> {

    Page<BulkUpload> findByCollegeIdAndEntityTypeOrderByCreatedAtDesc(Long collegeId, String entityType, Pageable pageable);

    Optional<BulkUpload> findByIdAndCollegeId(Long id, Long collegeId);

    /** The upload that uk_bulk_uploads_same_file (V5) says this file already is, if any. */
    @Query("""
           SELECT b FROM BulkUpload b
           WHERE b.college.id = :collegeId AND b.entityType = :entityType
             AND b.fileSha256 = :sha256 AND b.status <> 'FAILED'
           """)
    Optional<BulkUpload> findLiveBySameFile(@Param("collegeId") Long collegeId,
                                            @Param("entityType") String entityType,
                                            @Param("sha256") String sha256);

    @Modifying
    @Query("""
           UPDATE BulkUpload b SET b.totalRows = :total, b.successfulRows = :succeeded,
                  b.failedRows = :failed, b.lastProgressAt = :now
           WHERE b.id = :id AND b.status = 'PROCESSING'
           """)
    int updateProgress(@Param("id") Long id, @Param("total") int total, @Param("succeeded") int succeeded,
                       @Param("failed") int failed, @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
           UPDATE BulkUpload b SET b.totalRows = :total, b.successfulRows = :succeeded,
                  b.failedRows = :failed, b.status = :status, b.errorReport = :errorReport,
                  b.completedAt = :now, b.lastProgressAt = :now
           WHERE b.id = :id AND b.status = 'PROCESSING'
           """)
    int finish(@Param("id") Long id, @Param("total") int total, @Param("succeeded") int succeeded,
               @Param("failed") int failed, @Param("status") String status,
               @Param("errorReport") String errorReport, @Param("now") LocalDateTime now);

    /** Uploads whose importer stopped beating before {@code cutoff}. */
    @Modifying
    @Query("""
           UPDATE BulkUpload b SET b.status = 'FAILED', b.errorReport = :reason, b.completedAt = :now
           WHERE b.status = 'PROCESSING' AND COALESCE(b.lastProgressAt, b.createdAt) < :cutoff
           """)
    int failStale(@Param("cutoff") LocalDateTime cutoff, @Param("reason") String reason,
                  @Param("now") LocalDateTime now);
}
