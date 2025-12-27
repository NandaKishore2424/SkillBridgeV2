package com.skillbridge.feedback.repository;

import com.skillbridge.feedback.entity.Feedback;
import com.skillbridge.feedback.entity.FeedbackType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    List<Feedback> findByBatchId(Long batchId);
    List<Feedback> findByStudentId(Long studentId);
    List<Feedback> findByTrainerId(Long trainerId);
    List<Feedback> findByStudentIdAndType(Long studentId, FeedbackType type);
    List<Feedback> findByTrainerIdAndType(Long trainerId, FeedbackType type);
}
