package com.skillbridge.architecture;

import com.skillbridge.common.cache.L1CacheConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every cache has to be able to say what its hit rate is.
 *
 * <p>The whole argument for the two entries in {@code docs/CACHING_STRATEGY.md}
 * is that their keys are read again before they go stale. That is a number, and
 * a cache that cannot report it leaves the argument unfalsifiable — which is
 * worse than a cache that is measurably useless, because a useless one gets
 * removed.
 *
 * <p>Two ways to lose it silently, and this catches both. Dropping
 * {@code recordStats()} leaves Caffeine reporting zeroes forever rather than
 * failing. Registering a cache without {@code CaffeineCacheMetrics.monitor}
 * leaves it working perfectly and invisible. Neither shows up anywhere except
 * in a dashboard nobody is looking at yet.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+",
        disabledReason = "needs a PostgreSQL instance; set DATABASE_URL to run")
class CacheObservabilityTest {

    @Autowired private MeterRegistry registry;

    /**
     * Every cache the application registers, including the revocation window,
     * which is not a {@code @Cacheable} cache but is bounded, expiring and just
     * as worth watching.
     */
    private static final Set<String> EVERY_CACHE = Set.of(
            L1CacheConfig.ACTIVE_COLLEGES,
            L1CacheConfig.CURRICULUM,
            "auth:revoked");

    @Test
    @DisplayName("every cache reports hits, misses and evictions")
    void everyCacheIsInstrumented() {
        for (String cache : EVERY_CACHE) {
            assertThat(meterNamesFor(cache))
                    .as("""
                        Cache '%s' publishes no meters. Either CaffeineCacheMetrics.monitor \
                        was not called for it or recordStats() was dropped -- in both cases \
                        it keeps working and stops being measurable, and nothing fails.""",
                            cache)
                    .isNotEmpty()
                    .contains("cache.gets", "cache.evictions");
        }
    }

    @Test
    @DisplayName("hit and miss are separable, or the hit rate cannot be computed")
    void hitsAndMissesAreTagged() {
        // cache.gets carries result=hit / result=miss. Without the tag the
        // dashboard query in Phase 06 Task 7 -- hits over total -- has no
        // numerator, and the hit rate is unobtainable from a single counter.
        List<String> results = registry.find("cache.gets")
                .tag("cache", L1CacheConfig.CURRICULUM)
                .meters().stream()
                .map(m -> m.getId().getTag("result"))
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(results)
                .as("cache.gets on '%s' must be split by result, or hit rate is not derivable",
                        L1CacheConfig.CURRICULUM)
                .contains("hit", "miss");
    }

    private List<String> meterNamesFor(String cacheName) {
        return registry.getMeters().stream()
                .filter(m -> cacheName.equals(tag(m.getId().getTags(), "cache")))
                .map(m -> m.getId().getName())
                .distinct()
                .collect(Collectors.toList());
    }

    private static String tag(Iterable<Tag> tags, String key) {
        for (Tag t : tags) {
            if (t.getKey().equals(key)) {
                return t.getValue();
            }
        }
        return null;
    }
}
