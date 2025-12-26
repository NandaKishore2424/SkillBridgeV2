package com.skillbridge.trainer.repository;

import com.skillbridge.trainer.entity.Trainer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrainerRepository extends JpaRepository<Trainer, Long> {
    Optional<Trainer> findByUserId(Long userId);

    List<Trainer> findByCollegeId(Long collegeId);

    boolean existsByUserId(Long userId);

    long countByCollegeId(Long collegeId);
}
