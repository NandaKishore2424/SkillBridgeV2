package com.skillbridge.company.repository;

import com.skillbridge.company.entity.Company;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/**
 * Composable predicates for the admin company list.
 *
 * <p>Simpler than the student and trainer ones: everything searchable is a
 * column on {@code companies}, so nothing has to cross an association and the
 * only association handling is fetching {@code college} for the DTO's
 * {@code collegeName}. That fetch is still guarded on the query's result type,
 * because a paged Specification runs a second time for {@code count(...)} and a
 * fetch join is illegal there.
 */
public final class CompanySpecifications {

    private CompanySpecifications() {
    }

    /**
     * Scopes to one college, or to none.
     *
     * <p>A null {@code collegeId} means "every college" here, which is correct
     * only because the sole caller is a SYSTEM_ADMIN branch that has already
     * established the caller is unscoped. A college admin always passes an id.
     */
    public static Specification<Company> inCollege(Long collegeId) {
        return (root, query, cb) -> collegeId == null
                ? cb.conjunction()
                : cb.equal(root.get("college").get("id"), collegeId);
    }

    /** Name or domain, case-insensitive. */
    public static Specification<Company> matches(String term) {
        if (isBlank(term)) {
            return (root, query, cb) -> cb.conjunction();
        }
        String pattern = "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("domain")), pattern));
    }

    /** {@code FULL_TIME}, {@code INTERNSHIP} or {@code BOTH}. */
    public static Specification<Company> hasHiringType(String hiringType) {
        return (root, query, cb) -> isBlank(hiringType)
                ? cb.conjunction()
                : cb.equal(cb.upper(root.get("hiringType")), hiringType.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Fetches {@code college} so the DTO can read its name.
     *
     * <p>LEFT, not INNER: a company with no college would otherwise vanish from
     * the list rather than show a null name, and the mapper already handles a
     * null college.
     */
    public static Specification<Company> withCollege() {
        return (root, query, cb) -> {
            if (isRowQuery(query)) {
                root.fetch("college", JoinType.LEFT);
            }
            return cb.conjunction();
        };
    }

    private static boolean isRowQuery(CriteriaQuery<?> query) {
        return query != null
                && query.getResultType() != Long.class
                && query.getResultType() != long.class;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
