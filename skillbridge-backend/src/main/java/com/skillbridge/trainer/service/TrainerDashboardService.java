package com.skillbridge.trainer.service;

import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.entity.TrainerBatch;
import com.skillbridge.batch.repository.TrainerBatchRepository;
import com.skillbridge.enrollment.entity.Enrollment;
import com.skillbridge.enrollment.repository.EnrollmentRepository;
import com.skillbridge.student.entity.Student;
import com.skillbridge.trainer.dto.TrainerDashboardStatsDTO;
import com.skillbridge.trainer.dto.TrainerBatchDTO;
import com.skillbridge.trainer.dto.TrainerStudentDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TrainerDashboardService {
        private final TrainerBatchRepository trainerBatchRepository;
        private final EnrollmentRepository enrollmentRepository;

        public TrainerDashboardStatsDTO getDashboardStats(Long userId) {
                log.info("Getting dashboard stats for trainer userId: {}", userId);

                List<TrainerBatch> trainerBatches = trainerBatchRepository.findByTrainerUserId(userId);
                List<Long> batchIds = trainerBatches.stream()
                                .map(tb -> tb.getBatch().getId())
                                .collect(Collectors.toList());

                int assignedBatches = trainerBatches.size();

                long activeBatches = trainerBatches.stream()
                                .filter(tb -> "ACTIVE".equals(tb.getBatch().getStatus()))
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

        public List<TrainerBatchDTO> getTrainerBatches(Long userId) {
                log.info("Getting batches for trainer userId: {}", userId);

                List<TrainerBatch> trainerBatches = trainerBatchRepository.findByTrainerUserId(userId);

                return trainerBatches.stream()
                                .map(tb -> {
                                        Batch batch = tb.getBatch();
                                        int enrolledCount = enrollmentRepository.countByBatchId(batch.getId());

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
                                                        .enrolledCount(enrolledCount)
                                                        .syllabus(null) // TODO: add syllabus info when needed
                                                        .build();
                                })
                                .collect(Collectors.toList());
        }

        public List<TrainerStudentDTO> getBatchStudents(Long userId, Long batchId) {
                log.info("Getting students for batch {} by trainer userId: {}", batchId, userId);

                // Verify trainer has access to this batch
                boolean hasAccess = trainerBatchRepository.existsByTrainerUserIdAndBatchId(userId, batchId);
                if (!hasAccess) {
                        throw new RuntimeException("Trainer does not have access to this batch");
                }

                List<Enrollment> enrollments = enrollmentRepository.findByBatchId(batchId);

                return enrollments.stream()
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
                                })
                                .collect(Collectors.toList());
        }
}
