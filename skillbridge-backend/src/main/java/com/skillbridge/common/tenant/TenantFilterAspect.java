package com.skillbridge.common.tenant;

import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Enables the Hibernate {@code collegeFilter} on the session a query will
 * actually use.
 *
 * <p><b>What was wrong before.</b> {@code TenantFilter} — a servlet filter —
 * called {@code entityManager.unwrap(Session.class)} and enabled the filter
 * there. That works only while {@code open-in-view} keeps one session open for
 * the whole request. Phase 00 set {@code open-in-view: false}, after which each
 * transaction opens its own session and the servlet filter was enabling
 * {@code collegeFilter} on a session no query ever ran against. Nothing threw;
 * the filter was simply never applied, and had not been for months.
 *
 * <p>It was proven by adding a soft-delete filter the same way and watching a
 * deleted row stay visible in every list.
 *
 * <p><b>Why an aspect ordered inside the transaction.</b> The session a query
 * uses is the one bound to the thread by {@code TransactionInterceptor} when it
 * begins the transaction. To enable a filter on it, this advice has to run
 * <em>after</em> the transaction has begun and <em>before</em> the method body
 * — that is, nested inside the transaction advice. Spring orders lower values
 * outermost, so {@code TransactionConfig} pins the transaction advisor at 100
 * and this sits at 200.
 *
 * <p><b>What this does not cover.</b> Data access outside a transaction. Each
 * repository call then opens and closes its own session, and there is no
 * reliable moment to reach it. Those paths depend on the query naming its
 * college explicitly or on a {@link TenantGuard} check — which is how the
 * by-id endpoints are protected regardless. Treat this as the safety net it is,
 * not as the primary control.
 */
@Aspect
@Component
@Order(TenantFilterAspect.ORDER)
@RequiredArgsConstructor
@Slf4j
public class TenantFilterAspect {

    /** Must be greater than {@link TransactionConfig#TRANSACTION_ADVISOR_ORDER} so this runs inside it. */
    public static final int ORDER = 200;

    private final EntityManager entityManager;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)"
            + " || @within(org.springframework.transaction.annotation.Transactional)")
    public Object enableTenantFilter(ProceedingJoinPoint pjp) throws Throwable {
        applyFilter();
        return pjp.proceed();
    }

    private void applyFilter() {
        // Without an active transaction there is no bound session, and unwrapping
        // here would create a throwaway one — the exact bug this class replaces.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }

        AuthenticatedUser caller = SecurityUtils.currentUserIfPresent().orElse(null);
        if (caller == null || caller.isSystemAdmin() || caller.getCollegeId() == null) {
            // A SYSTEM_ADMIN is deliberately unscoped, and an unauthenticated
            // caller has no college to scope to.
            return;
        }

        try {
            Session session = entityManager.unwrap(Session.class);
            if (session.getEnabledFilter("collegeFilter") == null) {
                session.enableFilter("collegeFilter")
                        .setParameter("collegeId", caller.getCollegeId());
            }
        } catch (Exception ex) {
            // Loudly: a silently absent tenant filter is what caused this class
            // to be written.
            log.error("Could not enable collegeFilter for college {} — "
                    + "queries in this transaction are NOT tenant-scoped",
                    caller.getCollegeId(), ex);
        }
    }
}
