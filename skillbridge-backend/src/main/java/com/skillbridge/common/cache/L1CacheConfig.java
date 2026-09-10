package com.skillbridge.common.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The application's only cache tier.
 *
 * <p><b>One tier, not two, and that is a decision rather than an omission.</b>
 * Phase 06 specifies Caffeine at L1 and Redis at L2, and L2's distinguishing
 * feature is shared invalidation across instances. This application runs as a
 * single JVM, so L1 <em>is</em> the shared cache: evicting in process evicts for
 * everybody, and cross-instance staleness is bounded by a TTL that never
 * applies. Redis would add a network hop, a dependency and a circuit breaker to
 * coordinate replicas that do not exist. {@code docs/CACHING_STRATEGY.md} § 7
 * names the trigger that revives it — a second instance — and what has to change
 * that day.
 *
 * <p><b>What is in here was measured, and most candidates did not survive.</b>
 * Phase 06 proposed nine; three are here. The three busiest rows it proposed —
 * role lookups, user-plus-roles by id, and the skill catalogue — turned out to
 * have no reader at all once authentication stopped reading the database. The
 * same document records the measurements and the four entries that were dropped.
 *
 * <p><b>Two properties every entry here has to have</b>, because L1 hands out a
 * reference rather than a copy:
 *
 * <ol>
 *   <li>The cached value must be a DTO, never an entity. A cached entity is
 *       detached the moment its session closes, so touching a lazy association
 *       on it throws — and a managed one shared between requests is worse.</li>
 *   <li>Nobody may mutate what they read out. Every caller gets the same object,
 *       so a single {@code setX} corrupts it for every subsequent hit until the
 *       TTL expires.</li>
 * </ol>
 */
@Configuration
@EnableCaching
public class L1CacheConfig {

    /**
     * Active colleges, for the registration form's picker.
     *
     * <p>Global — no tenant in the key, deliberately. This is the one
     * unauthenticated list on the platform, so it is also the one key an
     * anonymous flood would land on, and it holds nothing tenant-specific.
     */
    public static final String ACTIVE_COLLEGES = "l1:activeColleges";

    /** A batch's curriculum tree, keyed by batch. */
    public static final String CURRICULUM = "l1:curriculum";

    /** A student's or trainer's dashboard counts, keyed by user. */
    public static final String DASHBOARD_STATS = "l1:dashboardStats";

    /**
     * Caches whose contents are the same for every tenant.
     *
     * <p>Read by {@code TenantScopedCacheKeyTest}, which fails the build when a
     * cache that is <em>not</em> in this set is keyed without a tenant. Adding a
     * name here is therefore an assertion that the data is genuinely global, and
     * it is the one line in this file worth reviewing carefully.
     */
    public static final java.util.Set<String> GLOBAL_CACHES =
            java.util.Set.of(ACTIVE_COLLEGES);

    @Bean
    public CaffeineCacheManager cacheManager(MeterRegistry meterRegistry) {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // Null is not a cacheable answer here. Every entry below is a list or a
        // counts object that is empty rather than absent, so a null would mean
        // the loader failed -- and caching a failure for thirty minutes is how a
        // transient error becomes an outage.
        manager.setAllowNullValues(false);

        // Per-cache rather than one spec for all three: the TTLs differ by more
        // than an order of magnitude, and picking one would mean either serving
        // dashboard counts half an hour stale or re-reading the college list
        // every minute.
        register(manager, meterRegistry, ACTIVE_COLLEGES, Duration.ofMinutes(30), 64);
        register(manager, meterRegistry, CURRICULUM, Duration.ofMinutes(15), 500);
        register(manager, meterRegistry, DASHBOARD_STATS, Duration.ofMinutes(1), 5_000);

        return manager;
    }

    /**
     * @param maximumSize the bound that actually matters here. The TTLs are
     *                    short and the per-user and per-batch caches grow with
     *                    the platform, so size is what keeps the heap finite;
     *                    Caffeine's W-TinyLFU admission means a one-off scan
     *                    does not evict the hot keys the way plain LRU would.
     */
    private void register(CaffeineCacheManager manager, MeterRegistry registry,
                          String name, Duration ttl, int maximumSize) {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                // Without this the hit rate is unobservable, and an unobservable
                // cache is a claim rather than a result -- the whole argument for
                // these three entries is that they are read again before they go
                // stale, which is a number, not an opinion.
                .recordStats()
                // Phase 06 § 2.1 also asks for refreshAfterWrite, to serve the
                // old value while one thread reloads. It is deliberately absent:
                // it needs a LoadingCache, and Caffeine refuses to build one
                // without a CacheLoader -- asserted in L1CacheConfigTest, because
                // "this option would do nothing" is worth proving rather than
                // claiming. Spring's @Cacheable populates through
                // Cache.get(key, Callable) and never registers a per-key loader,
                // so there is nothing for a refresh to call. Setting it anyway
                // would be configuration with no effect, which this project has
                // already been bitten by once (gotcha 9).
                .build();

        CaffeineCacheMetrics.monitor(registry, cache, name);
        manager.registerCustomCache(name, cache);
    }
}
