package com.skillbridge.common.tenant;

import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.exception.ResourceNotFoundException;

/**
 * Asserts that a resource belongs to the caller's college.
 *
 * <p><b>Why this is needed at all, given {@code TenantFilter} exists.</b>
 * {@link TenantFilter} enables the Hibernate {@code collegeFilter} for every
 * request, and that correctly scopes <em>queries</em> — which is why every list
 * endpoint returned only the caller's own rows. But a Hibernate filter is not
 * applied to a load by primary key: {@code findById} goes to
 * {@code Session.find()}, which checks the persistence context and the second
 * level cache before it ever builds a filtered query. So every by-id endpoint
 * was unscoped.
 *
 * <p>That was not theoretical. A second college's administrator could read the
 * first college's students, trainers, batches, companies and enrollment lists
 * by id — full names, email addresses and roll numbers — verified against the
 * live database before this class was written.
 *
 * <p><b>404, not 403.</b> Per the project's standing decision: a 403 confirms
 * that the id exists and belongs to someone, which is exactly what an attacker
 * enumerating ids wants to learn. An unauthorised read is indistinguishable
 * from a miss.
 *
 * <p>A SYSTEM_ADMIN has no college and is deliberately unscoped, so every check
 * passes for them.
 */
public final class TenantGuard {

    private TenantGuard() {
    }

    /**
     * Throws 404 unless the caller may see a resource in {@code resourceCollegeId}.
     *
     * <p>Call it immediately after loading the resource and before returning or
     * mutating anything derived from it.
     *
     * @param resourceCollegeId the owning college; {@code null} is treated as
     *                          not visible, since an untenanted row cannot be
     *                          shown to a tenant-scoped caller
     * @param resource          name for the 404 message, e.g. {@code "Batch"}
     * @param id                the id the caller asked for
     */
    public static void requireVisible(Long resourceCollegeId, String resource, Object id) {
        if (!isVisible(resourceCollegeId)) {
            throw ResourceNotFoundException.of(resource, id);
        }
    }

    /**
     * Whether the current caller may see a resource owned by the given college.
     *
     * <p>Use where a miss is already handled — inside an {@code Optional.filter}
     * that ends in {@code orElseThrow}, for instance — so ownership and
     * existence produce one identical 404.
     */
    public static boolean isVisible(Long resourceCollegeId) {
        AuthenticatedUser caller = SecurityUtils.currentUser();
        if (caller.isSystemAdmin()) {
            return true;
        }
        return resourceCollegeId != null && resourceCollegeId.equals(caller.getCollegeId());
    }
}
