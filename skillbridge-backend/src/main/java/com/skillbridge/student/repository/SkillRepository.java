package com.skillbridge.student.repository;

import com.skillbridge.student.entity.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillRepository extends JpaRepository<Skill, Long> {
    Optional<Skill> findByName(String name);

    List<Skill> findByCategory(String category);

    List<Skill> findByNameContainingIgnoreCase(String name);
}
