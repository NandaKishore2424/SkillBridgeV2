package com.skillbridge.student.service;

import com.skillbridge.batch.dto.BatchDTO;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.student.dto.*;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudentDashboardService {

    private final StudentRepository studentRepository;
    private final BatchRepository batchRepository;

    @Transactional(readOnly = true)
    public StudentDashboardStatsDTO getDashboardStats(Long userId) {
        log.debug("Getting dashboard stats for user: {}", userId);

        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(
                        () -> new RuntimeException("Student profile not found. Please complete your profile setup."));

        // For now, return empty stats - will be implemented when enrollment module
        // exists
        return StudentDashboardStatsDTO.builder()
                .enrolledBatches(0)
                .activeBatches(0)
                .completedBatches(0)
                .totalTopicsCompleted(0)
                .build();
    }

    @Transactional(readOnly = true)
    public List<RecommendedBatchDTO> getRecommendedBatches(Long userId) {
        log.debug("Getting recommended batches for user: {}", userId);

        // For now, return empty list - full recommendation logic can be implemented
        // later
        return new ArrayList<>();
    }

    @Transactional(readOnly = true)
    public List<BatchDTO> getAvailableBatches(Long collegeId) {
        log.debug("Getting available batches for college: {}", collegeId);

        // Find batches with status OPEN or ACTIVE
        List<Batch> batches = batchRepository.findByCollegeId(collegeId);

        return batches.stream()
                .filter(b -> "OPEN".equals(b.getStatus()) || "ACTIVE".equals(b.getStatus()))
                .map(this::convertToBatchDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<StudentBatchDTO> getStudentBatches(Long userId) {
        log.debug("Getting batches for student user: {}", userId);

        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        // For now, return empty list - will be implemented when enrollment module
        // exists
        return new ArrayList<>();
    }

    @Transactional(readOnly = true)
    public StudentBatchDTO getBatchDetails(Long userId, Long batchId) {
        log.debug("Getting batch {} details for user: {}", batchId, userId);

        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("Batch not found"));

        return convertToStudentBatchDTO(batch);
    }

    @Transactional
    public Map<String, Object> applyToBatch(Long userId, Long batchId) {
        log.debug("User {} applying to batch {}", userId, batchId);

        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("Batch not found"));

        // For now, return success response - will create enrollment when module exists
        Map<String, Object> result = new HashMap<>();
        result.put("id", 1L);
        result.put("batchId", batchId);
        result.put("status", "PENDING");
        result.put("appliedAt", LocalDateTime.now());
        result.put("message", "Application functionality coming soon");

        return result;
    }

    @Transactional(readOnly = true)
    public StudentProgressDTO getStudentProgress(Long userId, Long batchId) {
        log.debug("Getting progress for user {} in batch {}", userId, batchId);

        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("Batch not found"));

        // For now, return empty progress - will be implemented when progress module
        // exists
        return StudentProgressDTO.builder()
                .batchId(batchId)
                .batchName(batch.getName())
                .topics(new ArrayList<>())
                .build();
    }

    private BatchDTO convertToBatchDTO(Batch batch) {
        return BatchDTO.builder()
                .id(batch.getId())
                .name(batch.getName())
                .description(batch.getDescription())
                .status(batch.getStatus())
                .startDate(batch.getStartDate())
                .endDate(batch.getEndDate())
                .build();
    }

    private StudentBatchDTO convertToStudentBatchDTO(Batch batch) {
        StudentBatchDTO dto = new StudentBatchDTO();
        dto.setId(batch.getId());
        dto.setName(batch.getName());
        dto.setDescription(batch.getDescription());
        dto.setStatus(batch.getStatus());
        dto.setStartDate(batch.getStartDate());
        dto.setEndDate(batch.getEndDate());
        dto.setEnrolledAt(LocalDateTime.now());
        dto.setTrainers(new ArrayList<>());
        dto.setCompanies(new ArrayList<>());
        dto.setProgress(null);

        return dto;
    }
}
