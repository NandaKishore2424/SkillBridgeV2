package com.skillbridge.batch.repository;

import com.skillbridge.batch.entity.Batch;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BatchRepository extends JpaRepository<Batch, Long>, JpaSpecificationExecutor<Batch> {

    List<Batch> findByCollegeId(Long collegeId);

    Page<Batch> findByCollegeId(Long collegeId, Pageable pageable);

    long countByCollegeId(Long collegeId);

    long countByCollegeIdAndStatus(Long collegeId, String status);

    // --- Soft-delete aware reads -------------------------------------------
    // Batches are never hard deleted, so anything user-facing has to exclude
    // deleted rows explicitly. Keeping these alongside the unfiltered finders
    // rather than replacing them, because admin and reporting paths legitimately
    // want to see removed batches.

    List<Batch> findByCollegeIdAndDeletedAtIsNull(Long collegeId);

    Optional<Batch> findByIdAndDeletedAtIsNull(Long id);

    /**
     * Batches a student could apply to: open in their college, not already
     * applied for or enrolled in.
     *
     * <p>{@code :excludedIds} arrives from the caller as a list that is never
     * empty — an empty {@code IN ()} is a syntax error in some drivers, so the
     * caller substitutes a sentinel rather than this query guarding for it.
     */
    @Query("""
           SELECT b FROM Batch b
           WHERE b.college.id = :collegeId
             AND b.deletedAt IS NULL
             AND b.status IN ('OPEN', 'UPCOMING')
             AND b.id NOT IN :excludedIds
           ORDER BY b.startDate ASC
           """)
    List<Batch> findOpenForCollegeExcluding(@Param("collegeId") Long collegeId,
                                            @Param("excludedIds") Collection<Long> excludedIds);

    @Query(value = """
           SELECT b FROM Batch b
           WHERE b.college.id = :collegeId
             AND b.deletedAt IS NULL
             AND b.status IN ('OPEN', 'ACTIVE', 'UPCOMING')
           ORDER BY b.startDate ASC
           """,
           countQuery = """
           SELECT count(b) FROM Batch b
           WHERE b.college.id = :collegeId
             AND b.deletedAt IS NULL
             AND b.status IN ('OPEN', 'ACTIVE', 'UPCOMING')
           """)
    Page<Batch> findAvailableForCollege(@Param("collegeId") Long collegeId, Pageable pageable);

    /**
     * Load a batch for a capacity-sensitive write.
     *
     * <p>Takes a row lock so that concurrent applications to the same batch
     * serialise on it. Without this the capacity check is a read-then-write race:
     * two applicants both see one seat free and both take it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Batch b WHERE b.id = :id AND b.deletedAt IS NULL")
    Optional<Batch> findByIdForUpdate(@Param("id") Long id);

    // --- reads that feed BatchDTO -------------------------------------------
    //
    // BatchDTO carries collegeName plus trainer and company counts. With
    // open-in-view: false the session is closed by the time the controller maps
    // the entity, so every one of those is a LazyInitializationException on a
    // plain findBy. college is fetch-joined (a to-one, so pagination stays in
    // SQL); the two counts are aggregated for a whole page in one query each,
    // rather than touching the collections per row.
    //
    // The collections are deliberately NOT fetch-joined: Hibernate cannot
    // paginate a collection fetch in SQL and would silently fall back to doing
    // it in memory (HHH000104), reading the entire table to serve one page.

    @Query(value = "select b from Batch b join fetch b.college where b.college.id = :collegeId",
           countQuery = "select count(b) from Batch b where b.college.id = :collegeId")
    Page<Batch> findByCollegeIdWithCollege(@Param("collegeId") Long collegeId, Pageable pageable);

    @Query("select b from Batch b join fetch b.college where b.id = :id")
    Optional<Batch> findByIdWithCollege(@Param("id") Long id);

    // ------------------------------------------------------------------
    // Trainer assignments
    // ------------------------------------------------------------------
    //
    // These three read `batch_trainers`, the @ManyToMany join table on
    // Batch.trainers, which is the one BatchAssignmentService writes and the
    // one GET /admin/batches/{id}/trainers reads.
    //
    // They replace a second table, `trainer_batches`, mapped by a TrainerBatch
    // entity that the whole trainer side used to read. Nothing in the
    // application ever wrote it, so every trainer's dashboard was empty and
    // ProgressService could never authorise anyone to grade. See the 2026-09-08
    // entry in HANDOVER.md.
    //
    // Rooting the query at Batch rather than at the join row is not just
    // tidier: Batch carries @SQLRestriction("deleted_at IS NULL"), so a
    // soft-deleted batch drops out here automatically. The old query could
    // return one.
    //
    // `join b.trainers` is a plain join, not a fetch, so these paginate in SQL.

    /** Batches this trainer is assigned to, most recent intake first. */
    @Query(value = """
           SELECT b FROM Batch b
           JOIN b.trainers t
           WHERE t.user.id = :trainerUserId
           ORDER BY b.startDate DESC
           """,
           countQuery = """
           SELECT count(b) FROM Batch b
           JOIN b.trainers t
           WHERE t.user.id = :trainerUserId
           """)
    Page<Batch> findByTrainerUserId(@Param("trainerUserId") Long trainerUserId, Pageable pageable);

    /** Unpaged form, for the dashboard counters that aggregate over all of them. */
    @Query("""
           SELECT b FROM Batch b
           JOIN b.trainers t
           WHERE t.user.id = :trainerUserId
           ORDER BY b.startDate DESC
           """)
    List<Batch> findByTrainerUserId(@Param("trainerUserId") Long trainerUserId);

    /**
     * Is this trainer assigned to this batch?
     *
     * <p>The authorisation gate for every trainer-facing read and every grading
     * write, so it is deliberately a count in SQL rather than a load-and-check.
     */
    @Query("""
           SELECT count(b) > 0 FROM Batch b
           JOIN b.trainers t
           WHERE b.id = :batchId AND t.user.id = :trainerUserId
           """)
    boolean isTrainerAssignedToBatch(@Param("trainerUserId") Long trainerUserId,
                                     @Param("batchId") Long batchId);

    /** {@code [batchId, trainerCount]} rows. Left join so a batch with none still appears. */
    @Query("select b.id, count(t.id) from Batch b left join b.trainers t where b.id in :ids group by b.id")
    List<Object[]> countTrainersByBatchIds(@Param("ids") Collection<Long> ids);

    /** {@code [batchId, companyCount]} rows. */
    @Query("select b.id, count(c.id) from Batch b left join b.companies c where b.id in :ids group by b.id")
    List<Object[]> countCompaniesByBatchIds(@Param("ids") Collection<Long> ids);

    /**
     * One batch with its trainers, and each trainer's {@code user}, loaded.
     *
     * <p>{@code GET /admin/batches/{id}/trainers} maps each trainer to a DTO
     * carrying {@code email} and {@code isActive}, which live on {@code User}.
     * Both hops are lazy, so a plain {@code findById} threw
     * {@code LazyInitializationException} on the collection before it ever
     * reached the user.
     *
     * <p>LEFT joins throughout: an inner join would return no row at all for a
     * batch with no trainers, which the caller cannot distinguish from a batch
     * that does not exist. A collection fetch is safe here only because this
     * returns a single batch -- do not copy it onto a paginated query.
     */
    @Query("select distinct b from Batch b left join fetch b.trainers t left join fetch t.user where b.id = :id")
    Optional<Batch> findByIdWithTrainers(@Param("id") Long id);

    /** As {@link #findByIdWithTrainers}, for companies and their college. */
    @Query("select distinct b from Batch b left join fetch b.companies c left join fetch c.college where b.id = :id")
    Optional<Batch> findByIdWithCompanies(@Param("id") Long id);

    // --- reverse lookups: which batches does X belong to -------------------
    //
    // Each returns [ownerId, batchId] pairs for a whole page in one query. The
    // list DTOs expose these as counts; resolving them per row would be the
    // same N+1 that made GET /admin/students take ten seconds.

    @Query("select t.id, b.id from Batch b join b.trainers t where t.id in :trainerIds")
    List<Object[]> findBatchIdsByTrainerIds(@Param("trainerIds") Collection<Long> trainerIds);

    @Query("select c.id, b.id from Batch b join b.companies c where c.id in :companyIds")
    List<Object[]> findBatchIdsByCompanyIds(@Param("companyIds") Collection<Long> companyIds);

    /** Enrollment is a separate table, not a join table on Batch. */
    @Query("select e.student.id, e.batch.id from Enrollment e where e.student.id in :studentIds")
    List<Object[]> findBatchIdsByStudentIds(@Param("studentIds") Collection<Long> studentIds);

    /** {@code [batchId, enrolledCount]} for a page of batches, in one query. */
    @Query("select e.batch.id, count(e.id) from Enrollment e where e.batch.id in :batchIds group by e.batch.id")
    List<Object[]> countEnrollmentsByBatchIds(@Param("batchIds") Collection<Long> batchIds);

    /**
     * {@code [batchId, Trainer]} pairs for a page of batches, in one query.
     *
     * <p>For a screen that lists each batch <em>with</em> its trainers. Reading
     * {@code batch.getTrainers()} inside a map is two queries per row once
     * companies are read too; this is one for the page.
     *
     * <p>Returns pairs rather than fetch-joining the collection onto the page,
     * because Hibernate cannot paginate a collection fetch in SQL -- it would
     * read every row and page in memory.
     */
    @Query("select b.id, t from Batch b join b.trainers t where b.id in :batchIds")
    List<Object[]> findTrainersByBatchIds(@Param("batchIds") Collection<Long> batchIds);

    /** {@code [batchId, Company]} pairs for a page of batches. See above. */
    @Query("select b.id, c from Batch b join b.companies c where b.id in :batchIds")
    List<Object[]> findCompaniesByBatchIds(@Param("batchIds") Collection<Long> batchIds);

    /**
     * Live batches this trainer is assigned to. Guards trainer deletion.
     *
     * <p>Counts through the join table explicitly and filters {@code deletedAt}
     * itself rather than relying on {@code activeFilter}: a count that decides
     * whether a delete is refused should not depend on whether a request-scoped
     * filter happened to be enabled.
     */
    @Query("select count(b) from Batch b join b.trainers t where t.id = :trainerId and b.deletedAt is null")
    long countLiveBatchesForTrainer(@Param("trainerId") Long trainerId);

    /** Live batches this company is linked to. Guards company deletion. */
    @Query("select count(b) from Batch b join b.companies c where c.id = :companyId and b.deletedAt is null")
    long countLiveBatchesForCompany(@Param("companyId") Long companyId);
}
