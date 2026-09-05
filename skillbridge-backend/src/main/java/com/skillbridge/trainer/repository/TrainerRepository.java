package com.skillbridge.trainer.repository;

import com.skillbridge.trainer.entity.Trainer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrainerRepository extends JpaRepository<Trainer, Long> {
    Optional<Trainer> findByUser_Id(Long userId);

    /** Bulk form of {@link #findByUser_Id}, so a list of rows resolves in one query. */
    List<Trainer> findByUser_IdIn(Collection<Long> userIds);

    List<Trainer> findByCollegeId(Long collegeId);
    Page<Trainer> findByCollegeId(Long collegeId, Pageable pageable);

    boolean existsByUser_Id(Long userId);

    long countByCollegeId(Long collegeId);
}
