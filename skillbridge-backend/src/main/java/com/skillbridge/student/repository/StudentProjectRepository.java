package com.skillbridge.student.repository;

import com.skillbridge.student.entity.StudentProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface StudentProjectRepository extends JpaRepository<StudentProject, Long> {
    List<StudentProject> findByStudentId(Long studentId);

    List<StudentProject> findByStudentIdOrderByStartDateDesc(Long studentId);

    /**
     * Every row for a whole page of students, in one query.
     *
     * <p>The per-student finders above are an N+1 when a list endpoint maps a
     * page of students to DTOs: 15 students meant 30 extra round trips, and
     * against a database in another region that made GET /admin/students take
     * nearly ten seconds. The page appeared to hang.
     */
    @Query("select sp from StudentProject sp where sp.student.id in :studentIds order by sp.startDate desc")
    List<StudentProject> findByStudentIdIn(@Param("studentIds") Collection<Long> studentIds);
}
