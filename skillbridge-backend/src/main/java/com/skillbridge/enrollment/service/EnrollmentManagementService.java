package com.skillbridge.enrollment.service;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.enrollment.dto.*;
import com.skillbridge.enrollment.entity.Enrollment;
import com.skillbridge.enrollment.entity.EnrollmentRequest;
import com.skillbridge.enrollment.repository.EnrollmentRepository;
import com.skillbridge.enrollment.repository.EnrollmentRequestRepository;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for managing student enrollments and enrollment requests
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class EnrollmentManagementService {

        private final EnrollmentRepository enrollmentRepository;
        private final EnrollmentRequestRepository requestRepository;
        private final BatchRepository batchRepository;
        private final StudentRepository studentRepository;
        private final TrainerRepository trainerRepository;
        private final UserRepository userRepository;

        /**
         * Get all enrollments for a batch (Admin)
         */
        @Transactional(readOnly = true)
        public BatchEnrollmentDTO getBatchEnrollments(Long batchId) {
                log.info("Fetching enrollments for batch {}", batchId);

                Batch batch = batchRepository.findById(batchId)
                                .orElseThrow(() -> new RuntimeException("Batch not found with id: " + batchId));

                List<Enrollment> enrollments = enrollmentRepository.findByBatchId(batchId);

                List<EnrolledStudentDTO> students = enrollments.stream()
                                .map(e -> convertToEnrolledStudentDTO(e.getStudent()))
                                .collect(Collectors.toList());

                return BatchEnrollmentDTO.builder()
                                .batchId(batch.getId())
                                .batchName(batch.getName())
                                .enrolledStudents(students)
                                .enrolledCount(students.size())
                                .build();
        }

        /**
         * Enroll a student in a batch (Admin)
         */
        public EnrolledStudentDTO enrollStudent(Long batchId, Long studentId) {
                log.info("Enrolling student {} in batch {}", studentId, batchId);

                Batch batch = batchRepository.findById(batchId)
                                .orElseThrow(() -> new RuntimeException("Batch not found with id: " + batchId));

                Student student = studentRepository.findById(studentId)
                                .orElseThrow(() -> new RuntimeException("Student not found with id: " + studentId));

                // Check if already enrolled
                Optional<Enrollment> existing = enrollmentRepository.findByBatchIdAndStudentId(batchId, studentId);
                if (existing.isPresent()) {
                        throw new RuntimeException("Student is already enrolled in this batch");
                }

                Enrollment enrollment = Enrollment.builder()
                                .batch(batch)
                                .student(student)
                                .build();

                enrollmentRepository.save(enrollment);
                log.info("Enrolled student {} in batch {}", studentId, batchId);

                return convertToEnrolledStudentDTO(student);
        }

        /**
         * Remove a student from a batch (Admin)
         */
        public void removeStudent(Long batchId, Long studentId) {
                log.info("Removing student {} from batch {}", studentId, batchId);

                Enrollment enrollment = enrollmentRepository.findByBatchIdAndStudentId(batchId, studentId)
                                .orElseThrow(() -> new RuntimeException("Student is not enrolled in this batch"));

                enrollmentRepository.delete(enrollment);
                log.info("Removed student {} from batch {}", studentId, batchId);
        }

        /**
         * Create an enrollment request (Trainer)
         */
        public EnrollmentRequestDTO createEnrollmentRequest(Long trainerId, CreateEnrollmentRequestDTO request) {
                log.info("Trainer {} creating {} request for student {} in batch {}",
                                trainerId, request.getRequestType(), request.getStudentId(), request.getBatchId());

                Batch batch = batchRepository.findById(request.getBatchId())
                                .orElseThrow(() -> new RuntimeException("Batch not found"));

                Student student = studentRepository.findById(request.getStudentId())
                                .orElseThrow(() -> new RuntimeException("Student not found"));

                Trainer trainer = trainerRepository.findById(trainerId)
                                .orElseThrow(() -> new RuntimeException("Trainer not found"));

                // Validate request type
                EnrollmentRequest.RequestType requestType;
                try {
                        requestType = EnrollmentRequest.RequestType.valueOf(request.getRequestType().toUpperCase());
                } catch (IllegalArgumentException e) {
                        throw new RuntimeException("Invalid request type. Must be ADD or REMOVE");
                }

                // Check if there's already a pending request for this combination
                Optional<EnrollmentRequest> existingRequest = requestRepository.findPendingRequest(
                                request.getBatchId(), request.getStudentId(), requestType);

                if (existingRequest.isPresent()) {
                        throw new RuntimeException("A pending request already exists for this student and batch");
                }

                // Validate the request makes sense
                boolean isEnrolled = enrollmentRepository.findByBatchIdAndStudentId(
                                request.getBatchId(), request.getStudentId()).isPresent();

                if (requestType == EnrollmentRequest.RequestType.ADD && isEnrolled) {
                        throw new RuntimeException("Student is already enrolled in this batch");
                }

                if (requestType == EnrollmentRequest.RequestType.REMOVE && !isEnrolled) {
                        throw new RuntimeException("Student is not enrolled in this batch");
                }

                EnrollmentRequest enrollmentRequest = EnrollmentRequest.builder()
                                .batch(batch)
                                .student(student)
                                .trainer(trainer)
                                .requestType(requestType)
                                .status(EnrollmentRequest.RequestStatus.PENDING)
                                .reason(request.getReason())
                                .build();

                EnrollmentRequest savedRequest = requestRepository.save(enrollmentRequest);
                log.info("Created enrollment request {}", savedRequest.getId());

                return convertToRequestDTO(savedRequest);
        }

        /**
         * Get all pending requests (Admin)
         */
        @Transactional(readOnly = true)
        public List<EnrollmentRequestDTO> getPendingRequests() {
                log.info("Fetching all pending enrollment requests");

                List<EnrollmentRequest> requests = requestRepository.findAllPending();

                return requests.stream()
                                .map(this::convertToRequestDTO)
                                .collect(Collectors.toList());
        }

        /**
         * Get trainer's own requests
         */
        @Transactional(readOnly = true)
        public List<EnrollmentRequestDTO> getTrainerRequests(Long trainerId) {
                log.info("Fetching requests for trainer {}", trainerId);

                List<EnrollmentRequest> requests = requestRepository.findByTrainerIdAndStatus(
                                trainerId, EnrollmentRequest.RequestStatus.PENDING);

                return requests.stream()
                                .map(this::convertToRequestDTO)
                                .collect(Collectors.toList());
        }

        /**
         * Approve an enrollment request (Admin)
         */
        public EnrollmentRequestDTO approveRequest(Long requestId, Long adminUserId) {
                log.info("Admin {} approving request {}", adminUserId, requestId);

                EnrollmentRequest request = requestRepository.findById(requestId)
                                .orElseThrow(() -> new RuntimeException("Request not found"));

                if (!request.isPending()) {
                        throw new RuntimeException("Request is not pending");
                }

                User admin = userRepository.findById(adminUserId)
                                .orElseThrow(() -> new RuntimeException("Admin user not found"));

                request.approve(admin);

                // Execute the request
                if (request.getRequestType() == EnrollmentRequest.RequestType.ADD) {
                        // Check if not already enrolled
                        if (!enrollmentRepository.findByBatchIdAndStudentId(
                                        request.getBatch().getId(), request.getStudent().getId()).isPresent()) {
                                Enrollment enrollment = Enrollment.builder()
                                                .batch(request.getBatch())
                                                .student(request.getStudent())
                                                .build();
                                enrollmentRepository.save(enrollment);
                                log.info("Added student {} to batch {}", request.getStudent().getId(),
                                                request.getBatch().getId());
                        }
                } else if (request.getRequestType() == EnrollmentRequest.RequestType.REMOVE) {
                        enrollmentRepository.findByBatchIdAndStudentId(
                                        request.getBatch().getId(), request.getStudent().getId())
                                        .ifPresent(enrollment -> {
                                                enrollmentRepository.delete(enrollment);
                                                log.info("Removed student {} from batch {}",
                                                                request.getStudent().getId(),
                                                                request.getBatch().getId());
                                        });
                }

                EnrollmentRequest savedRequest = requestRepository.save(request);
                log.info("Approved and executed request {}", requestId);

                return convertToRequestDTO(savedRequest);
        }

        /**
         * Reject an enrollment request (Admin)
         */
        public EnrollmentRequestDTO rejectRequest(Long requestId, Long adminUserId) {
                log.info("Admin {} rejecting request {}", adminUserId, requestId);

                EnrollmentRequest request = requestRepository.findById(requestId)
                                .orElseThrow(() -> new RuntimeException("Request not found"));

                if (!request.isPending()) {
                        throw new RuntimeException("Request is not pending");
                }

                User admin = userRepository.findById(adminUserId)
                                .orElseThrow(() -> new RuntimeException("Admin user not found"));

                request.reject(admin);

                EnrollmentRequest savedRequest = requestRepository.save(request);
                log.info("Rejected request {}", requestId);

                return convertToRequestDTO(savedRequest);
        }

        /**
         * Convert Student to DTO
         */
        private EnrolledStudentDTO convertToEnrolledStudentDTO(Student student) {
                return EnrolledStudentDTO.builder()
                                .studentId(student.getId())
                                .fullName(student.getFullName())
                                .rollNumber(student.getRollNumber())
                                .email(student.getUser().getEmail())
                                .department(student.getBranch())
                                .year(student.getYear())
                                .build();
        }

        /**
         * Convert EnrollmentRequest to DTO
         */
        private EnrollmentRequestDTO convertToRequestDTO(EnrollmentRequest request) {
                return EnrollmentRequestDTO.builder()
                                .id(request.getId())
                                .batchId(request.getBatch().getId())
                                .batchName(request.getBatch().getName())
                                .studentId(request.getStudent().getId())
                                .studentName(request.getStudent().getFullName())
                                .studentRollNumber(request.getStudent().getRollNumber())
                                .trainerId(request.getTrainer().getId())
                                .trainerName(request.getTrainer().getFullName())
                                .requestType(request.getRequestType().name())
                                .status(request.getStatus().name())
                                .reason(request.getReason())
                                .reviewedBy(request.getReviewedBy() != null ? request.getReviewedBy().getId() : null)
                                .reviewedByName(request.getReviewedBy() != null ? request.getReviewedBy().getEmail()
                                                : null)
                                .reviewedAt(request.getReviewedAt())
                                .createdAt(request.getCreatedAt())
                                .build();
        }
}
