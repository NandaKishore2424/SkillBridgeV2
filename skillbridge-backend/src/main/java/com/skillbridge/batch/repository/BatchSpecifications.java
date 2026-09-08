package com.skillbridge.batch.repository;

import com.skillbridge.batch.entity.Batch;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/**
 * Composable predicates for the admin batch list.
 *
 * <p>Specifications rather than a finder per combination: search, status and
 * date are three independent, optional filters, and derived finders for every
 * subset is eight methods that become sixteen the next time somebody adds a
 * filter.
 *
 * <p>Each returns a no-op ({@code conjunction}) for a null or blank argument,
 * so a caller can always chain all of them and let the absent ones fall away.
 * That is what keeps the controller free of {@code if} branches.
 */
public final class BatchSpecifications {

    private BatchSpecifications() {
    }

    public static Specification<Batch> inCollege(Long collegeId) {
        return (root, query, cb) -> collegeId == null
                ? cb.conjunction()
                : cb.equal(root.get("college").get("id"), collegeId);
    }

    public static Specification<Batch> hasStatus(String status) {
        return (root, query, cb) -> isBlank(status)
                ? cb.conjunction()
                // Status is a small, closed vocabulary written in upper case.
                // Comparing case-insensitively means ?status=active works, which
                // is what anyone hand-testing the API will type first.
                : cb.equal(cb.upper(root.get("status")), status.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Case-insensitive substring match on name or description.
     *
     * <p>This is a leading-wildcard LIKE, which cannot use a b-tree index and is
     * a sequential scan. That is an acceptable trade at the sizes here and the
     * wrong one later: Phase 05 adds the {@code pg_trgm} GIN index that makes it
     * indexable, and this is the query that needs it.
     */
    public static Specification<Batch> matches(String term) {
        if (isBlank(term)) {
            return (root, query, cb) -> cb.conjunction();
        }
        String pattern = "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("description")), pattern));
    }

    /**
     * Fetches {@code college} so the DTO mapper can read its name.
     *
     * <p>The result-type guard is not optional. A paged Specification runs
     * twice — once for the rows and once for {@code count(...)} — and a fetch
     * join on the count query throws
     * {@code "query specified join fetching, but the owner of the fetched
     * association was not present in the select list"}. Checking the result
     * type is how you tell the two executions apart, and leaving it out is the
     * single most common way this pattern breaks.
     */
    public static Specification<Batch> withCollege() {
        return (root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("college", JoinType.LEFT);
            }
            return cb.conjunction();
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
