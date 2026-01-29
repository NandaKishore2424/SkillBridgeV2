package com.skillbridge.batch.repository;

import com.skillbridge.batch.entity.TrainerBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrainerBatchRepository extends JpaRepository<TrainerBatch, Long> {
    List<TrainerBatch> findByTrainerUserId(Long trainerUserId);

    List<TrainerBatch> findByBatchId(Long batchId);

    boolean existsByTrainerUserIdAndBatchId(Long trainerUserId, Long batchId);
}
