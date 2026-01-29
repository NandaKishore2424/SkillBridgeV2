package com.skillbridge.timeline.service;

import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.syllabus.entity.SyllabusTopic;
import com.skillbridge.syllabus.repository.SyllabusTopicRepository;
import com.skillbridge.timeline.dto.CreateSessionRequest;
import com.skillbridge.timeline.dto.TimelineSessionDTO;
import com.skillbridge.timeline.dto.UpdateSessionRequest;
import com.skillbridge.timeline.entity.TimelineSession;
import com.skillbridge.timeline.repository.TimelineSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing batch timeline sessions
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class TimelineService {

    private final TimelineSessionRepository sessionRepository;
    private final BatchRepository batchRepository;
    private final SyllabusTopicRepository topicRepository;

    /**
     * Get all sessions for a batch
     */
    @Transactional(readOnly = true)
    public List<TimelineSessionDTO> getTimelineByBatchId(Long batchId) {
        log.info("Fetching timeline for batch {}", batchId);

        List<TimelineSession> sessions = sessionRepository.findByBatchIdOrderBySessionNumber(batchId);

        return sessions.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Create a new session for a batch
     */
    public TimelineSessionDTO createSession(Long batchId, CreateSessionRequest request) {
        log.info("Creating session {} for batch {}", request.getSessionNumber(), batchId);

        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("Batch not found with id: " + batchId));

        // Check if session number already exists
        if (sessionRepository.existsByBatchIdAndSessionNumber(batchId, request.getSessionNumber())) {
            throw new RuntimeException(
                    "Session number " + request.getSessionNumber() + " already exists for this batch");
        }

        SyllabusTopic topic = null;
        if (request.getTopicId() != null) {
            topic = topicRepository.findById(request.getTopicId())
                    .orElseThrow(() -> new RuntimeException("Topic not found with id: " + request.getTopicId()));
        }

        TimelineSession session = TimelineSession.builder()
                .batch(batch)
                .sessionNumber(request.getSessionNumber())
                .title(request.getTitle())
                .description(request.getDescription())
                .topic(topic)
                .plannedDate(request.getPlannedDate())
                .build();

        TimelineSession savedSession = sessionRepository.save(session);
        log.info("Created session {}", savedSession.getId());

        return convertToDTO(savedSession);
    }

    /**
     * Update a session
     */
    public TimelineSessionDTO updateSession(Long sessionId, UpdateSessionRequest request) {
        log.info("Updating session {}", sessionId);

        TimelineSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found with id: " + sessionId));

        if (request.getSessionNumber() != null) {
            // Check if new session number conflicts
            if (!request.getSessionNumber().equals(session.getSessionNumber())) {
                if (sessionRepository.existsByBatchIdAndSessionNumber(
                        session.getBatch().getId(), request.getSessionNumber())) {
                    throw new RuntimeException("Session number " + request.getSessionNumber() + " already exists");
                }
                session.setSessionNumber(request.getSessionNumber());
            }
        }

        if (request.getTitle() != null) {
            session.setTitle(request.getTitle());
        }

        if (request.getDescription() != null) {
            session.setDescription(request.getDescription());
        }

        if (request.getTopicId() != null) {
            SyllabusTopic topic = topicRepository.findById(request.getTopicId())
                    .orElseThrow(() -> new RuntimeException("Topic not found with id: " + request.getTopicId()));
            session.setTopic(topic);
        }

        if (request.getPlannedDate() != null) {
            session.setPlannedDate(request.getPlannedDate());
        }

        TimelineSession updatedSession = sessionRepository.save(session);
        return convertToDTO(updatedSession);
    }

    /**
     * Delete a session
     */
    public void deleteSession(Long sessionId) {
        log.info("Deleting session {}", sessionId);

        if (!sessionRepository.existsById(sessionId)) {
            throw new RuntimeException("Session not found with id: " + sessionId);
        }

        sessionRepository.deleteById(sessionId);
        log.info("Deleted session {}", sessionId);
    }

    /**
     * Convert entity to DTO
     */
    private TimelineSessionDTO convertToDTO(TimelineSession session) {
        return TimelineSessionDTO.builder()
                .id(session.getId())
                .sessionNumber(session.getSessionNumber())
                .title(session.getTitle())
                .description(session.getDescription())
                .topicId(session.getTopic() != null ? session.getTopic().getId() : null)
                .topicName(session.getTopic() != null ? session.getTopic().getName() : null)
                .plannedDate(session.getPlannedDate())
                .build();
    }
}
