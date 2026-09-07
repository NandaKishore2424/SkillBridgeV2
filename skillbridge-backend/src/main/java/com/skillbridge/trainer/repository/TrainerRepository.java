package com.skillbridge.trainer.repository;

import com.skillbridge.trainer.entity.Trainer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrainerRepository extends JpaRepository<Trainer, Long> {
    Optional<Trainer> findByUser_Id(Long userId);

    /**
     * Trainers assigned to one batch, a page at a time.
     *
     * <p>Selects the trainers directly through the join table rather than
     * loading the batch and paging its {@code trainers} collection. That
     * distinction matters: Hibernate cannot push LIMIT/OFFSET into SQL for a
     * collection fetch, so it reads every row and pages in memory, which is
     * exactly the failure paginating is meant to prevent. Selecting the
     * association's target as the query root makes it an ordinary join.
     *
     * <p>{@code user} is fetch-joined because the DTO reads the email and
     * active flag off it.
     */
    @Query(value = "select t from Batch b join b.trainers t join fetch t.user "
                 + "where b.id = :batchId order by t.fullName",
           countQuery = "select count(t) from Batch b join b.trainers t where b.id = :batchId")
    Page<Trainer> findByBatch(@Param("batchId") Long batchId, Pageable pageable);

    /** Bulk form of {@link #findByUser_Id}, so a list of rows resolves in one query. */
    List<Trainer> findByUser_IdIn(Collection<Long> userIds);

    List<Trainer> findByCollegeId(Long collegeId);
    Page<Trainer> findByCollegeId(Long collegeId, Pageable pageable);

    boolean existsByUser_Id(Long userId);

    long countByCollegeId(Long collegeId);

    /**
     * Page of trainers with {@code user} eagerly joined.
     *
     * <p>TrainerDTO exposes {@code email} and {@code isActive}, both of which
     * live on {@code User}. That association is lazy, and with
     * {@code open-in-view: false} the mapper runs after the session closes, so
     * a plain {@code findByCollegeId} throws {@code LazyInitializationException}
     * on the first row. A to-one fetch join keeps pagination in SQL.
     */
    @Query(value = "select t from Trainer t join fetch t.user where t.college.id = :collegeId",
           countQuery = "select count(t) from Trainer t where t.college.id = :collegeId")
    Page<Trainer> findByCollegeIdWithUser(@Param("collegeId") Long collegeId, Pageable pageable);

    /** Single trainer by user id, with {@code user} joined. See {@link #findByCollegeIdWithUser}. */
    @Query("select t from Trainer t join fetch t.user where t.user.id = :userId")
    Optional<Trainer> findByUserIdWithUser(@Param("userId") Long userId);

    /** Single trainer by primary key, with {@code user} joined. */
    @Query("select t from Trainer t join fetch t.user where t.id = :id")
    Optional<Trainer> findByIdWithUser(@Param("id") Long id);

    /** Unpaginated college listing, with {@code user} joined. */
    @Query("select t from Trainer t join fetch t.user where t.college.id = :collegeId")
    List<Trainer> findByCollegeIdWithUser(@Param("collegeId") Long collegeId);
}
