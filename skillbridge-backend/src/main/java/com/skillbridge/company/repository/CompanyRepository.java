package com.skillbridge.company.repository;

import com.skillbridge.company.entity.Company;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Reads over {@code companies}.
 *
 * <p>The {@code ...WithCollege} finders exist because {@code CompanyDTO} carries
 * {@code collegeName}. {@code Company.college} is a lazy {@code @ManyToOne}, and
 * with {@code open-in-view: false} the session is closed by the time the
 * controller maps the entity — so reading the name off the proxy throws
 * {@code LazyInitializationException}. Reading {@code getCollege().getId()} does
 * not, because a proxy knows its own identifier, which is why this surfaced only
 * on the name.
 *
 * <p>Prefer these over {@link #findAll} and {@link #findById} on any path that
 * builds a DTO.
 */
@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {

    /**
     * Companies mapped to one batch, a page at a time.
     *
     * <p>Selected through the join table rather than by paging the batch's
     * {@code companies} collection — see {@code TrainerRepository.findByBatch}
     * for why a collection fetch cannot be paginated in SQL.
     */
    @Query(value = "select c from Batch b join b.companies c left join fetch c.college "
                 + "where b.id = :batchId order by c.name",
           countQuery = "select count(c) from Batch b join b.companies c where b.id = :batchId")
    Page<Company> findByBatch(@Param("batchId") Long batchId, Pageable pageable);

    long countByCollegeId(Long collegeId);

    Page<Company> findByCollegeId(Long collegeId, Pageable pageable);

    /**
     * Left join: {@code college} is nullable on this entity, and an inner join
     * would silently drop every company that has no college.
     */
    @Query(value = "select c from Company c left join fetch c.college",
           countQuery = "select count(c) from Company c")
    Page<Company> findAllWithCollege(Pageable pageable);

    @Query(value = "select c from Company c left join fetch c.college where c.college.id = :collegeId",
           countQuery = "select count(c) from Company c where c.college.id = :collegeId")
    Page<Company> findByCollegeIdWithCollege(@Param("collegeId") Long collegeId, Pageable pageable);

    @Query("select c from Company c left join fetch c.college where c.id = :id")
    Optional<Company> findByIdWithCollege(@Param("id") Long id);
}
