package com.skillbridge.student.repository;

import com.skillbridge.student.entity.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long>, JpaSpecificationExecutor<Student> {
    Optional<Student> findByUser_Id(Long userId);

    /** Bulk form of {@link #findByUser_Id}, so a list of rows resolves in one query. */
    List<Student> findByUser_IdIn(Collection<Long> userIds);

    List<Student> findByCollegeId(Long collegeId);
    Page<Student> findByCollegeId(Long collegeId, Pageable pageable);

    Optional<Student> findByRollNumberAndCollegeId(String rollNumber, Long collegeId);

    boolean existsByRollNumberAndCollegeId(String rollNumber, Long collegeId);

    boolean existsByUser_Id(Long userId);

    long countByCollegeId(Long collegeId);

    /**
     * Page of students with {@code user} eagerly joined.
     *
     * <p>StudentDTO exposes {@code email} and {@code isActive}, both of which
     * live on {@code User}. That association is lazy, and with
     * {@code open-in-view: false} the mapper runs after the session closes, so
     * a plain {@code findByCollegeId} throws {@code LazyInitializationException}
     * on the first row. A to-one fetch join keeps pagination in SQL.
     */
    @Query(value = "select s from Student s join fetch s.user where s.college.id = :collegeId",
           countQuery = "select count(s) from Student s where s.college.id = :collegeId")
    Page<Student> findByCollegeIdWithUser(@Param("collegeId") Long collegeId, Pageable pageable);

    /** Single student by user id, with {@code user} joined. See {@link #findByCollegeIdWithUser}. */
    @Query("select s from Student s join fetch s.user where s.user.id = :userId")
    Optional<Student> findByUserIdWithUser(@Param("userId") Long userId);

    /** Single student by primary key, with {@code user} joined. */
    @Query("select s from Student s join fetch s.user where s.id = :id")
    Optional<Student> findByIdWithUser(@Param("id") Long id);

    /** Unpaginated college listing, with {@code user} joined. */
    @Query("select s from Student s join fetch s.user where s.college.id = :collegeId")
    List<Student> findByCollegeIdWithUser(@Param("collegeId") Long collegeId);
}
