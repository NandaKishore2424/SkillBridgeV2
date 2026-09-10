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
 * Phase 06 proposed nine; two are here. The three busiest rows it proposed —
 * role lookups, user-plus-roles by id, and the skill catalogue — turned out to
 * have no reader at all once authentication stopped reading the database.
 *
 * <p><b>Both survivors share one property, and it is the rule to apply to the
 * next candidate: their key is shared between users.</b> The active-college list
 * is the same for everybody, and a batch's curriculum is the same for every
 * student and trainer on it, so one user's read warms it for the next. That is
 * something a server cache can do and a browser cannot.
 *
 * <p>A per-user key cannot do it, and this frontend already caches per-user
 * responses for five minutes with {@code refetchOnWindowFocus} off — five times
 * the TTL a server-side dashboard-stats cache was going to have. That entry was
 * dropped for that reason rather than built and left to run at a hit rate
 * nobody would have looked at. {@code docs/CACHING_STRATEGY.md} § 5.
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
@EnableCaching(order = L1CacheConfig.CACHE_ADVISOR_ORDER)
public class L1CacheConfig {

    /**
     * Outside the transaction advisor, which {@link
     * com.skillbridge.common.tenant.TransactionConfig} pins at 100. Lower value
     * = outermost.
     *
     * <p><b>Measured, not assumed.</b> At the default both advisors sit at
     * {@code Ordered.LOWEST_PRECEDENCE} and the tie resolved with the
     * transaction outermost, which meant <b>a cache hit still opened a Hibernate
     * session and checked out a connection</b> — three warm hits produced three
     * sessions and zero statements. The cache was saving the round trips for the
     * queries and none for the connection, on a project that spent Phase 05
     * taking {@code GET /admin/students} from 5.00 checkouts to 1.00.
     *
     * <p>It also quietly capped the thundering herd: callers queued for a
     * connection <em>before</em> consulting the cache, so the pool serialised
     * them and a 200-caller stampede on a cold key produced 2 loads rather than
     * the number a real pool would have allowed. A limiter you did not intend is
     * not a limiter you can keep.
     *
     * <p>Two further consequences, both improvements: the value is now written to
     * the cache <em>after</em> the transaction commits rather than inside it, and
     * a {@code @CacheEvict} on a write fires after commit for the same reason.
     *
     * <p>Still comfortably inside Spring Security's method interceptors, which
     * order themselves near {@code Integer.MIN_VALUE} — a cache that ran outside
     * authorisation would serve a hit to a caller who was never checked, which is
     * the same defect {@code CurriculumReader} exists to avoid one level down.
     */
    public static final int CACHE_ADVISOR_ORDER = 50;

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
