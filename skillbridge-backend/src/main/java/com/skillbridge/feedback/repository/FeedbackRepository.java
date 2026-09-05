package com.skillbridge.feedback.repository;

import com.skillbridge.feedback.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads over the user &#8596; user {@code feedback} table.
 *
 * <p>Every finder fetch-joins {@code batch}, {@code fromUser} and
 * {@code toUser}. With {@code open-in-view: false} a plain derived query would
 * return proxies that blow up during DTO mapping, and even inside the
 * transaction it would be one extra select per row per association — a
 * three-way N+1 on a list endpoint.
 */
@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    String FETCH = "select f from Feedback f "
            + "join fetch f.batch b "
            + "join fetch f.fromUser "
            + "join fetch f.toUser ";

    @Query(FETCH + "where b.id = :batchId order by f.createdAt desc")
    List<Feedback> findByBatch(@Param("batchId") Long batchId);

    /** Feedback written about a user. */
    @Query(FETCH + "where f.toUser.id = :userId order by f.createdAt desc")
    List<Feedback> findReceivedBy(@Param("userId") Long userId);

    /** Feedback written by a user. */
    @Query(FETCH + "where f.fromUser.id = :userId order by f.createdAt desc")
    List<Feedback> findGivenBy(@Param("userId") Long userId);

    /** Everything a user is party to, in either direction. */
    @Query(FETCH + "where f.fromUser.id = :userId or f.toUser.id = :userId order by f.createdAt desc")
    List<Feedback> findInvolving(@Param("userId") Long userId);
}
