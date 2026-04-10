package com.skillbridge.trainer.repository;

import com.skillbridge.trainer.entity.Trainer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrainerRepository extends JpaRepository<Trainer, Long> {
    Optional<Trainer> findByUser_Id(Long userId);

    List<Trainer> findByCollegeId(Long collegeId);
    Page<Trainer> findByCollegeId(Long collegeId, Pageable pageable);

    boolean existsByUser_Id(Long userId);

    long countByCollegeId(Long collegeId);
}
