package com.skillbridge.feedback.service;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.feedback.dto.FeedbackRequestDTO;
import com.skillbridge.feedback.dto.FeedbackResponseDTO;
import com.skillbridge.feedback.entity.Feedback;
import com.skillbridge.feedback.entity.FeedbackType;
import com.skillbridge.feedback.repository.FeedbackRepository;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final BatchRepository batchRepository;

    @Override
    @Transactional
    public FeedbackResponseDTO createFeedback(FeedbackRequestDTO request, String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        Batch batch = batchRepository.findById(request.getBatchId())
                .orElseThrow(() -> new EntityNotFoundException("Batch not found"));

        Feedback feedback = new Feedback();
        feedback.setBatch(batch);
        feedback.setRating(request.getRating());
        feedback.setCategory(request.getCategory());
        feedback.setComments(request.getComments());
        feedback.setType(request.getType());

        if (request.getType() == FeedbackType.STUDENT_TO_TRAINER) {
            Student student = studentRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new EntityNotFoundException("Student profile not found"));
            feedback.setStudent(student);
            
            // Trainer is the target
            if (request.getTrainerId() == null) {
                throw new IllegalArgumentException("Trainer ID is required for Student to Trainer feedback");
            }
            Trainer trainer = trainerRepository.findById(request.getTrainerId())
                    .orElseThrow(() -> new EntityNotFoundException("Trainer not found"));
            feedback.setTrainer(trainer);

        } else if (request.getType() == FeedbackType.TRAINER_TO_STUDENT) {
            Trainer trainer = trainerRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new EntityNotFoundException("Trainer profile not found"));
            feedback.setTrainer(trainer);

            // Student is the target
            if (request.getStudentId() == null) {
                throw new IllegalArgumentException("Student ID is required for Trainer to Student feedback");
            }
            Student student = studentRepository.findById(request.getStudentId())
                    .orElseThrow(() -> new EntityNotFoundException("Student not found"));
            feedback.setStudent(student);
        } else {
            throw new IllegalArgumentException("Invalid feedback type");
        }

        Feedback savedFeedback = feedbackRepository.save(feedback);
        return mapToDTO(savedFeedback);
    }

    @Override
    public List<FeedbackResponseDTO> getFeedbackByBatch(Long batchId) {
        return feedbackRepository.findByBatchId(batchId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<FeedbackResponseDTO> getFeedbackByStudent(Long studentId) {
        return feedbackRepository.findByStudentId(studentId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<FeedbackResponseDTO> getFeedbackByTrainer(Long trainerId) {
        return feedbackRepository.findByTrainerId(trainerId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<FeedbackResponseDTO> getMyFeedback(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        // If user is student, get feedback GIVEN by them (to trainers) AND RECEIVED by them (from trainers)
        // Or maybe just all feedback involving them?
        // Let's return all feedback involving this user.
        
        // Check roles to determine if student or trainer
        boolean isStudent = user.getRoles().stream().anyMatch(r -> r.getName().equals("STUDENT"));
        boolean isTrainer = user.getRoles().stream().anyMatch(r -> r.getName().equals("TRAINER"));

        if (isStudent) {
            Student student = studentRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new EntityNotFoundException("Student profile not found"));
            return feedbackRepository.findByStudentId(student.getId()).stream()
                    .map(this::mapToDTO)
                    .collect(Collectors.toList());
        } else if (isTrainer) {
            Trainer trainer = trainerRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new EntityNotFoundException("Trainer profile not found"));
            return feedbackRepository.findByTrainerId(trainer.getId()).stream()
                    .map(this::mapToDTO)
                    .collect(Collectors.toList());
        }
        
        return List.of();
    }

    private FeedbackResponseDTO mapToDTO(Feedback feedback) {
        return FeedbackResponseDTO.builder()
                .id(feedback.getId())
                .studentId(feedback.getStudent().getId())
                .studentName(feedback.getStudent().getFullName())
                .trainerId(feedback.getTrainer().getId())
                .trainerName(feedback.getTrainer().getFullName())
                .batchId(feedback.getBatch().getId())
                .batchName(feedback.getBatch().getName())
                .type(feedback.getType())
                .rating(feedback.getRating())
                .category(feedback.getCategory())
                .comments(feedback.getComments())
                .createdAt(feedback.getCreatedAt())
                .build();
    }
}
