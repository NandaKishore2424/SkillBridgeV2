package com.skillbridge.student.repository;

import com.skillbridge.student.entity.Student;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/**
 * Composable predicates for the admin student list.
 *
 * <p>Each returns a no-op for a null or blank argument, so the controller can
 * chain all of them and let the absent ones fall away.
 *
 * <p>Two things here are easy to get wrong and both are silent:
 *
 * <ul>
 *   <li>A paged Specification is executed twice, once for the rows and once for
 *       {@code count(...)}. A fetch join on the count execution throws
 *       {@code "query specified join fetching, but the owner of the fetched
 *       association was not present in the select list"}, so the fetch is
 *       applied only to the row query.</li>
 *   <li>{@code Root.getJoins()} does not contain fetches — they live in
 *       {@code getFetches()} — so a predicate that reaches for
 *       {@code root.join("user")} on its own produces a <em>second</em> join
 *       alongside the fetch. For this to-one association that happens to return
 *       the same rows, which is why it would never be noticed; on a to-many it
 *       multiplies them and corrupts {@code totalElements}. Everything here
 *       goes through {@link #userJoin}, which yields exactly one.</li>
 * </ul>
 */
public final class StudentSpecifications {

    private StudentSpecifications() {
    }

    public static Specification<Student> inCollege(Long collegeId) {
        return (root, query, cb) -> collegeId == null
                ? cb.conjunction()
                : cb.equal(root.get("college").get("id"), collegeId);
    }

    /**
     * Matches the fields the list screen already searched client-side: name,
     * roll number, degree, branch — and email, which lives on {@code user}.
     */
    public static Specification<Student> matches(String term) {
        if (isBlank(term)) {
            return (root, query, cb) -> cb.conjunction();
        }
        String pattern = "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("fullName")), pattern),
                cb.like(cb.lower(root.get("rollNumber")), pattern),
                cb.like(cb.lower(root.get("degree")), pattern),
                cb.like(cb.lower(root.get("branch")), pattern),
                cb.like(cb.lower(userJoin(root, query).get("email")), pattern));
    }

    /**
     * Active or deactivated, read from the login rather than the profile.
     *
     * <p>{@code isActive} is a {@code users} column; the student row has no such
     * flag. The list screen presents it as the student's status, so filtering on
     * it has to cross the association.
     */
    public static Specification<Student> isActive(Boolean active) {
        return (root, query, cb) -> active == null
                ? cb.conjunction()
                : cb.equal(userJoin(root, query).get("isActive"), active);
    }

    /** Fetches {@code user} on the row query so the DTO mapper can read it. */
    public static Specification<Student> withUser() {
        return (root, query, cb) -> {
            userJoin(root, query);
            return cb.conjunction();
        };
    }

    /**
     * The one join to {@code user}, created on first use and reused after.
     *
     * <p>On the row query it is a fetch, so the association is loaded with the
     * page instead of one select per row. On the count query it is a plain join,
     * because a fetch there is illegal. Hibernate's fetch implements
     * {@link Join}, which is what makes a single accessor able to serve both.
     */
    @SuppressWarnings("unchecked")
    private static Join<Student, ?> userJoin(Root<Student> root, CriteriaQuery<?> query) {
        for (Join<Student, ?> existing : root.getJoins()) {
            if (existing.getAttribute().getName().equals("user")) {
                return existing;
            }
        }
        for (Fetch<Student, ?> existing : root.getFetches()) {
            if (existing.getAttribute().getName().equals("user")) {
                return (Join<Student, ?>) existing;
            }
        }
        if (isRowQuery(query)) {
            // Hibernate's Fetch is a Join underneath; the cast goes through
            // Object because the two interfaces are unrelated to javac.
            return (Join<Student, ?>) (Object) root.fetch("user", JoinType.INNER);
        }
        return root.join("user", JoinType.INNER);
    }

    /** False for the {@code count(...)} execution of a paged Specification. */
    private static boolean isRowQuery(CriteriaQuery<?> query) {
        return query != null
                && query.getResultType() != Long.class
                && query.getResultType() != long.class;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
