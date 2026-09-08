package com.skillbridge.trainer.service;

import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.enrollment.entity.Enrollment;
import com.skillbridge.enrollment.repository.EnrollmentRepository;
import com.skillbridge.student.entity.Student;
import com.skillbridge.trainer.dto.TrainerDashboardStatsDTO;
import com.skillbridge.trainer.dto.TrainerBatchDTO;
import com.skillbridge.trainer.dto.TrainerStudentDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;
import com.skillbridge.common.exception.ForbiddenException;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TrainerDashboardService {
        private final BatchRepository batchRepository;
        private final EnrollmentRepository enrollmentRepository;

        public TrainerDashboardStatsDTO getDashboardStats(Long userId) {
                log.info("Getting dashboard stats for trainer userId: {}", userId);

                List<Batch> assigned = batchRepository.findByTrainerUserId(userId);
                List<Long> batchIds = assigned.stream()
                                .map(Batch::getId)
                                .collect(Collectors.toList());

                int assignedBatches = assigned.size();

                long activeBatches = assigned.stream()
                                .filter(b -> "ACTIVE".equals(b.getStatus()))
                                .count();

                int totalStudents = 0;
                if (!batchIds.isEmpty()) {
                        totalStudents = enrollmentRepository.countByBatchIdIn(batchIds);
                }

                return TrainerDashboardStatsDTO.builder()
                                .assignedBatches(assignedBatches)
                                .activeBatches((int) activeBatches)
                                .totalStudents(totalStudents)
                                .pendingProgressUpdates(0) // TODO: implement when progress tracking is added
                                .build();
        }

        public Page<TrainerBatchDTO> getTrainerBatches(Long userId, Pageable pageable) {
                log.info("Getting batches for trainer userId: {}", userId);

                Page<Batch> batches = batchRepository.findByTrainerUserId(userId, pageable);

                // One grouped count for the whole page. This was
                // countByBatchId(batch.getId()) inside the map -- a query per
                // row, so twelve batches cost thirteen statements where three
                // cost four. QueryEfficiencyTest caught it.
                Map<Long, Long> enrolled = countsByBatchId(batchRepository.countEnrollmentsByBatchIds(
                                batches.getContent().stream().map(Batch::getId).toList()));

                return batches
                                .map(batch -> {
                                        long enrolledCount = enrolled.getOrDefault(batch.getId(), 0L);

                                        return TrainerBatchDTO.builder()
                                                        .id(batch.getId())
                                                        .name(batch.getName())
                                                        .description(batch.getDescription())
                                                        .status(batch.getStatus())
                                                        .startDate(batch.getStartDate() != null ? batch.getStartDate()
                                                                        .format(DateTimeFormatter.ISO_LOCAL_DATE)
                                                                        : null)
                                                        .endDate(batch.getEndDate() != null ? batch.getEndDate().format(
                                                                        DateTimeFormatter.ISO_LOCAL_DATE) : null)
                                                        .enrolledCount((int) enrolledCount)
                                                        .syllabus(null) // TODO: add syllabus info when needed
                                                        .build();
                                });
        }

        /** {@code [batchId, count]} rows folded into a lookup. */
        private static Map<Long, Long> countsByBatchId(List<Object[]> rows) {
                Map<Long, Long> counts = new HashMap<>();
                for (Object[] row : rows) {
                        counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
                }
                return counts;
        }

        public Page<TrainerStudentDTO> getBatchStudents(Long userId, Long batchId, Pageable pageable) {
                log.info("Getting students for batch {} by trainer userId: {}", batchId, userId);

                // Verify trainer has access to this batch
                boolean hasAccess = batchRepository.isTrainerAssignedToBatch(userId, batchId);
                if (!hasAccess) {
                        throw new ForbiddenException("Trainer does not have access to this batch");
                }

                return enrollmentRepository.findByBatch(batchId, pageable)
                                .map(enrollment -> {
                                        Student student = enrollment.getStudent();
                                        return TrainerStudentDTO.builder()
                                                        .id(student.getId())
                                                        .userId(student.getUser().getId())
                                                        .rollNumber(student.getRollNumber())
                                                        .fullName(student.getFullName())
                                                        .email(student.getUser().getEmail())
                                                        .enrolledAt(enrollment.getEnrolledAt()
                                                                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                                                        .progressSummary(null) // TODO: add progress when tracking is
                                                                               // implemented
                                                        .build();
                                });
        }
}
