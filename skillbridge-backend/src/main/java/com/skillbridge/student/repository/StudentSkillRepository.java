package com.skillbridge.student.repository;

import com.skillbridge.student.entity.StudentSkill;
import com.skillbridge.student.entity.StudentSkillId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentSkillRepository extends JpaRepository<StudentSkill, StudentSkillId> {
    List<StudentSkill> findByStudentId(Long studentId);

    void deleteByStudentIdAndSkillId(Long studentId, Long skillId);
}
