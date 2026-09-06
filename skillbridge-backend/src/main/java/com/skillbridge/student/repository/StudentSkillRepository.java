package com.skillbridge.student.repository;

import com.skillbridge.student.entity.StudentSkill;
import com.skillbridge.student.entity.StudentSkillId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface StudentSkillRepository extends JpaRepository<StudentSkill, StudentSkillId> {
    List<StudentSkill> findByStudentId(Long studentId);

    void deleteByStudentIdAndSkillId(Long studentId, Long skillId);

    /**
     * Every row for a whole page of students, in one query.
     *
     * <p>The per-student finders above are an N+1 when a list endpoint maps a
     * page of students to DTOs: 15 students meant 30 extra round trips, and
     * against a database in another region that made GET /admin/students take
     * nearly ten seconds. The page appeared to hang.
     */
    @Query("select ss from StudentSkill ss join fetch ss.skill where ss.student.id in :studentIds")
    List<StudentSkill> findByStudentIdIn(@Param("studentIds") Collection<Long> studentIds);
}
