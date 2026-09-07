package com.skillbridge.progress.repository;

import com.skillbridge.progress.domain.ProgressStatus;
import com.skillbridge.progress.entity.TopicProgress;
import com.skillbridge.progress.repository.projection.BatchProgressAggregate;
import com.skillbridge.progress.repository.projection.StudentBatchCompletion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TopicProgressRepository extends JpaRepository<TopicProgress, Long> {

    Optional<TopicProgress> findByStudentIdAndTopicId(Long studentId, Long topicId);

    boolean existsByStudentIdAndTopicId(Long studentId, Long topicId);

    /**
     * Every row for one student in one batch, with the curriculum tree fetched.
     *
     * <p>The fetch joins are load-bearing: {@code open-in-view} is off, so a lazy
     * load during JSON serialisation throws rather than quietly firing another
     * query. Anything the mapper touches has to be fetched here.
     */
    @Query("""
           SELECT tp FROM TopicProgress tp
           JOIN FETCH tp.topic t
           JOIN FETCH t.submodule sm
           JOIN FETCH sm.module m
           WHERE tp.student.id = :studentId
             AND tp.batchId = :batchId
           ORDER BY m.displayOrder, sm.displayOrder, t.displayOrder
           """)
    List<TopicProgress> findFullProgressForStudentInBatch(@Param("studentId") Long studentId,
                                                          @Param("batchId") Long batchId);

    /**
     * The trainer's grading grid for one topic: every enrolled student's row.
     */
    @Query(value = """
           SELECT tp FROM TopicProgress tp
           JOIN FETCH tp.student s
           WHERE tp.topic.id = :topicId
           ORDER BY s.fullName
           """,
           countQuery = """
           SELECT count(tp) FROM TopicProgress tp
           WHERE tp.topic.id = :topicId
           """)
    Page<TopicProgress> findGradingGridForTopic(@Param("topicId") Long topicId, Pageable pageable);

    List<TopicProgress> findByStudentIdAndBatchIdAndTopicIdIn(Long studentId, Long batchId,
                                                              Collection<Long> topicIds);

    /**
     * Rows for a set of (student, topic) pairs in one batch — the read half of a
     * bulk grading call, fetched in a single query rather than one per student.
     */
    @Query("""
           SELECT tp FROM TopicProgress tp
           WHERE tp.batchId = :batchId
             AND tp.topic.id = :topicId
             AND tp.student.id IN :studentIds
           """)
    List<TopicProgress> findForBulkGrading(@Param("batchId") Long batchId,
                                           @Param("topicId") Long topicId,
                                           @Param("studentIds") Collection<Long> studentIds);

    /**
     * Everything the summary table needs for one (student, batch), in one pass.
     *
     * <p>{@code FILTER (WHERE ...)} is the SQL-standard conditional aggregate and
     * is cheaper than {@code COUNT(CASE WHEN ...)} because Postgres skips the
     * expression entirely for non-matching rows. JPQL has no equivalent, which is
     * why this is native — an acceptable cost, since the project is not going to
     * change database and pretending otherwise would make the query worse.
     */
    @Query(value = """
           SELECT
             COUNT(*)                                                   AS total,
             COUNT(*) FILTER (WHERE status = 'COMPLETED')               AS completed,
             COUNT(*) FILTER (WHERE status = 'IN_PROGRESS')             AS in_progress,
             COUNT(*) FILTER (WHERE status = 'NEEDS_IMPROVEMENT')       AS needs_work,
             COALESCE(SUM(CASE status
                            WHEN 'COMPLETED'         THEN 1.0
                            WHEN 'IN_PROGRESS'       THEN 0.5
                            WHEN 'NEEDS_IMPROVEMENT' THEN 0.25
                            ELSE 0 END), 0)                             AS weighted_sum,
             AVG(score)                                                 AS average_score,
             MAX(updated_at)                                            AS last_activity_at
           FROM topic_progress
           WHERE student_id = :studentId AND batch_id = :batchId
           """, nativeQuery = true)
    BatchProgressAggregate aggregateForStudentInBatch(@Param("studentId") Long studentId,
                                                      @Param("batchId") Long batchId);

    /**
     * Weighted completion for every student in a batch, in one query.
     *
     * <p>Used by the trainer's batch overview. The alternative — a per-student
     * aggregate in a loop — is the N+1 that makes a class of forty students forty
     * round trips to a database that is not in the same building.
     */
    @Query(value = """
           SELECT
             student_id                                                 AS studentId,
             COUNT(*)                                                   AS total,
             COUNT(*) FILTER (WHERE status = 'COMPLETED')               AS completed,
             COALESCE(SUM(CASE status
                            WHEN 'COMPLETED'         THEN 1.0
                            WHEN 'IN_PROGRESS'       THEN 0.5
                            WHEN 'NEEDS_IMPROVEMENT' THEN 0.25
                            ELSE 0 END), 0)                             AS weightedSum
           FROM topic_progress
           WHERE batch_id = :batchId
           GROUP BY student_id
           """, nativeQuery = true)
    List<StudentBatchCompletion> summariseByBatch(@Param("batchId") Long batchId);

    long countByBatchIdAndStatus(Long batchId, ProgressStatus status);

    long countByStudentIdAndStatus(Long studentId, ProgressStatus status);

    long countByStudentId(Long studentId);

    /**
     * Seed progress rows for one student across every topic in a batch.
     *
     * <p>A single {@code INSERT ... SELECT} rather than a loop of {@code save()}
     * calls: a sixty-topic syllabus becomes one statement instead of sixty, and
     * the whole thing is atomic. {@code ON CONFLICT DO NOTHING} makes it safely
     * repeatable, which matters because enrollment can be retried and because
     * topics added to a syllabus later need backfilling into existing students.
     *
     * <p>batch_id and college_id are filled by the {@code trg_progress_denorm}
     * trigger, so they are not listed here.
     */
    @Modifying
    @Query(value = """
           INSERT INTO topic_progress (student_id, syllabus_topic_id, status, created_at, updated_at)
           SELECT :studentId, t.id, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
           FROM   syllabus_topics t
           JOIN   syllabus_submodules sm ON t.submodule_id = sm.id
           JOIN   syllabus_modules    m  ON sm.module_id  = m.id
           WHERE  m.batch_id = :batchId
           ON CONFLICT (student_id, syllabus_topic_id) DO NOTHING
           """, nativeQuery = true)
    int initialiseProgressForStudent(@Param("studentId") Long studentId,
                                     @Param("batchId") Long batchId);

    /**
     * Seed progress for every student already enrolled in a batch. Used when a
     * trainer adds topics to a syllabus that students are partway through.
     */
    @Modifying
    @Query(value = """
           INSERT INTO topic_progress (student_id, syllabus_topic_id, status, created_at, updated_at)
           SELECT e.student_id, t.id, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
           FROM   enrollments e
           JOIN   syllabus_modules    m  ON m.batch_id = e.batch_id
           JOIN   syllabus_submodules sm ON sm.module_id = m.id
           JOIN   syllabus_topics     t  ON t.submodule_id = sm.id
           WHERE  e.batch_id = :batchId
             AND  e.status = 'ACTIVE'
           ON CONFLICT (student_id, syllabus_topic_id) DO NOTHING
           """, nativeQuery = true)
    int backfillProgressForBatch(@Param("batchId") Long batchId);

    @Modifying
    @Query("DELETE FROM TopicProgress tp WHERE tp.student.id = :studentId AND tp.batchId = :batchId")
    int deleteForStudentInBatch(@Param("studentId") Long studentId, @Param("batchId") Long batchId);
}
