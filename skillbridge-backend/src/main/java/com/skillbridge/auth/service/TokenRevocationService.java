package com.skillbridge.auth.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Closes the window between deactivating an account and its access token
 * expiring.
 *
 * <p><b>The gap this exists for.</b> Since 2026-09-09 authentication builds the
 * principal from the token's claims instead of reloading the user, which took a
 * round trip off every authenticated request. The cost was that the token's
 * lifetime became the revocation window: an admin deactivates a student, and
 * that student keeps working for up to fifteen minutes because nothing on the
 * request path reads {@code is_active} any more.
 *
 * <p><b>Why this is not simply "put the database read back".</b> That read was
 * one of the two connection checkouts every request made, against a pooler that
 * allows fifteen connections for the whole project, at roughly 150 ms a round
 * trip. Undoing it would undo a measured result and reverse a decision that was
 * taken deliberately. This costs an in-process hash lookup instead: no
 * statement, no connection, no round trip — asserted by
 * {@code RevocationCostsNothingTest}.
 *
 * <p><b>Timestamps, not a boolean.</b> Each entry records <em>when</em> the
 * account was deactivated, and a token is refused when it was issued before that
 * moment. A boolean would have to be cleared by hand on reactivation and would
 * wrongly refuse the token issued after it; comparing against {@code iat} gets
 * reactivation right for free and cannot refuse a token that did not exist yet.
 *
 * <p><b>Bounded by construction.</b> Entries expire after the access-token TTL,
 * because a token issued before a deactivation that old has expired anyway and
 * the entry can no longer refuse anything. So this holds at most the accounts
 * deactivated in the last fifteen minutes, which is not a number that grows.
 *
 * <p><b>One instance.</b> Like the L1 caches, this is in-process, which is
 * complete while the application runs as a single JVM and is not the day a
 * second replica appears — a revocation recorded on instance 1 would not reach
 * instance 2. {@code docs/CACHING_STRATEGY.md} § 7 names that trigger; this is
 * one more thing that changes with it, and it is the one that matters most,
 * because the others go stale and this one goes wrong.
 */
@Service
@Slf4j
public class TokenRevocationService {

    private final com.github.benmanes.caffeine.cache.Cache<Long, Instant> revokedAt;

    public TokenRevocationService(JwtService jwtService, MeterRegistry meterRegistry) {
        // Exactly the access-token lifetime. Shorter would reopen the window it
        // closes; longer would keep entries that can no longer refuse anything,
        // since every token issued before them has already expired.
        Duration window = Duration.ofSeconds(jwtService.accessTokenTtlSeconds());

        this.revokedAt = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(10_000)
                .recordStats()
                .build();

        CaffeineCacheMetrics.monitor(meterRegistry, revokedAt, "auth:revoked");
        log.info("Token revocation window is {}s, matching the access-token TTL", window.toSeconds());
    }

    /**
     * Refuse every access token this user already holds.
     *
     * <p>Call on deactivation. Safe to call repeatedly — the later timestamp
     * simply widens the set of tokens refused, and every token it refuses was
     * issued to an account that is now inactive.
     */
    public void revoke(Long userId) {
        if (userId == null) {
            return;
        }
        revokedAt.put(userId, Instant.now());
        log.info("Revoked existing access tokens for user {}", userId);
    }

    /**
     * Let this user's existing tokens work again.
     *
     * <p>Call on reactivation. Without it a reactivated account would stay locked
     * out for the remainder of the window, which reads to the user as the
     * reactivation not having worked.
     */
    public void restore(Long userId) {
        if (userId == null) {
            return;
        }
        if (revokedAt.asMap().remove(userId) != null) {
            log.info("Restored access for user {}", userId);
        }
    }

    /**
     * @param issuedAt the token's {@code iat}. A token issued after the
     *                 deactivation is honoured: it can only have come from a
     *                 login or refresh, and both refuse an inactive account.
     */
    public boolean isRevoked(Long userId, Instant issuedAt) {
        if (userId == null || issuedAt == null) {
            return false;
        }
        Instant cutoff = revokedAt.getIfPresent(userId);
        return cutoff != null && issuedAt.isBefore(cutoff);
    }
}
