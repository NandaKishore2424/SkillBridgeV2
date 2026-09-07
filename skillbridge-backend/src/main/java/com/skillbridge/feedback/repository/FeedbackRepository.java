package com.skillbridge.feedback.repository;

import com.skillbridge.feedback.entity.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Reads over the user &#8596; user {@code feedback} table.
 *
 * <p>Every finder fetch-joins {@code batch}, {@code fromUser} and
 * {@code toUser}. With {@code open-in-view: false} a plain derived query would
 * return proxies that blow up during DTO mapping, and even inside the
 * transaction it would be one extra select per row per association — a
 * three-way N+1 on a list endpoint.
 *
 * <p>All three finders are paged. Feedback is append-only and never pruned, so
 * an unpaginated {@code findInvolving} grows without limit for the life of the
 * account.
 *
 * <p>Each paged query declares its own {@code countQuery}. Spring Data cannot
 * derive a count from a query containing {@code join fetch} — it would either
 * fail to parse or count the joined rows — and the fetches are pure decoration
 * for a count anyway.
 *
 * <p>Paginating these is safe despite the fetch joins because all three
 * associations are to-one. Hibernate can push {@code LIMIT}/{@code OFFSET} into
 * SQL for a to-one join; a collection fetch is the case it cannot, where it
 * silently pages in memory instead.
 */
@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    String FETCH = "select f from Feedback f "
            + "join fetch f.batch b "
            + "join fetch f.fromUser "
            + "join fetch f.toUser ";

    String COUNT = "select count(f) from Feedback f ";

    @Query(value = FETCH + "where b.id = :batchId order by f.createdAt desc",
           countQuery = COUNT + "where f.batch.id = :batchId")
    Page<Feedback> findByBatch(@Param("batchId") Long batchId, Pageable pageable);

    /** Feedback written about a user. */
    @Query(value = FETCH + "where f.toUser.id = :userId order by f.createdAt desc",
           countQuery = COUNT + "where f.toUser.id = :userId")
    Page<Feedback> findReceivedBy(@Param("userId") Long userId, Pageable pageable);

    /** Everything a user is party to, in either direction. */
    @Query(value = FETCH + "where f.fromUser.id = :userId or f.toUser.id = :userId order by f.createdAt desc",
           countQuery = COUNT + "where f.fromUser.id = :userId or f.toUser.id = :userId")
    Page<Feedback> findInvolving(@Param("userId") Long userId, Pageable pageable);
}
