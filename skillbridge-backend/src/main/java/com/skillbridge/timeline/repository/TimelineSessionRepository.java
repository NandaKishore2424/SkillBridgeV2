package com.skillbridge.timeline.repository;

import com.skillbridge.timeline.entity.TimelineSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for TimelineSession entities
 */
@Repository
public interface TimelineSessionRepository extends JpaRepository<TimelineSession, Long> {

    /**
     * Find all sessions for a batch, ordered by session number
     */
    List<TimelineSession> findByBatchIdOrderBySessionNumber(Long batchId);

    /**
     * Check if a session with the given number exists for a batch
     */
    boolean existsByBatchIdAndSessionNumber(Long batchId, Integer sessionNumber);

    /**
     * Find a session by batch ID and session number
     */
    Optional<TimelineSession> findByBatchIdAndSessionNumber(Long batchId, Integer sessionNumber);

    /**
     * Count sessions for a batch
     */
    long countByBatchId(Long batchId);

    /**
     * Find sessions for a batch within a date range
     */
    @Query("SELECT s FROM TimelineSession s " +
            "WHERE s.batch.id = :batchId " +
            "AND s.plannedDate BETWEEN :startDate AND :endDate " +
            "ORDER BY s.plannedDate, s.sessionNumber")
    List<TimelineSession> findByBatchIdAndPlannedDateBetween(
            @Param("batchId") Long batchId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Find sessions linked to a specific topic
     */
    List<TimelineSession> findByTopicId(Long topicId);

    /**
     * Find upcoming sessions (with planned dates in the future)
     */
    @Query("SELECT s FROM TimelineSession s " +
            "WHERE s.batch.id = :batchId " +
            "AND s.plannedDate >= :today " +
            "ORDER BY s.plannedDate, s.sessionNumber")
    List<TimelineSession> findUpcomingSessionsByBatchId(
            @Param("batchId") Long batchId,
            @Param("today") LocalDate today);

    /**
     * Delete all sessions for a batch
     */
    void deleteByBatchId(Long batchId);
}
