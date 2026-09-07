package com.skillbridge.batch.repository;

import com.skillbridge.batch.entity.TrainerBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrainerBatchRepository extends JpaRepository<TrainerBatch, Long> {
    List<TrainerBatch> findByTrainerUserId(Long trainerUserId);

    /**
     * One trainer's batches, a page at a time, with the batch fetched.
     *
     * <p>The DTO reads the batch's name, status and dates, so without the fetch
     * this is one extra select per row.
     */
    @Query(value = "select tb from TrainerBatch tb join fetch tb.batch b "
                 + "where tb.trainer.user.id = :trainerUserId order by b.startDate desc",
           countQuery = "select count(tb) from TrainerBatch tb where tb.trainer.user.id = :trainerUserId")
    Page<TrainerBatch> findByTrainerUser(@Param("trainerUserId") Long trainerUserId, Pageable pageable);

    List<TrainerBatch> findByBatchId(Long batchId);

    boolean existsByTrainerUserIdAndBatchId(Long trainerUserId, Long batchId);
}
