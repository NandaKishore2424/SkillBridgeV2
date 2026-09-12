package com.skillbridge.common.throttle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Token-bucket rate limiting, keyed by identity and tiered by what the endpoint
 * costs.
 *
 * <p>This replaces a filter with four defects, three of which made it something
 * an attacker could step around rather than merely a blunt instrument:
 *
 * <ol>
 *   <li><b>An unbounded map.</b> Buckets were held in a {@code ConcurrentHashMap}
 *       that was never evicted, keyed by the whole access token. Tokens rotate
 *       every fifteen minutes, so every active user added a permanent entry four
 *       times an hour, for the life of the process.</li>
 *   <li><b>The limit reset on refresh.</b> Same cause: a new token was a new key,
 *       so the quota was cleared by using the application normally.</li>
 *   <li><b>{@code X-Forwarded-For} read from the left</b>, which the client
 *       controls — a forged value per request bought a fresh bucket per
 *       request. {@link RateLimitKeyResolver} reads from the right now, and
 *       ignores the header entirely while nothing of ours appends to it.</li>
 *   <li><b>One limit for everything</b>, so login and a dashboard read shared a
 *       budget that could not be right for both.</li>
 * </ol>
 *
 * <p><b>Ordering matters and changed with this.</b> The filter now runs after
 * authentication, because keying by user id needs a principal — before it, the
 * only thing available was the token string, which is how the original came to
 * key on the credential. An unauthenticated or badly-signed request simply has no
 * principal and falls back to its address.
 *
 * <p><b>It fails open.</b> If the bucket store throws, the request is let through
 * with an error log and a metric. A limiter that rejects everything when its own
 * machinery breaks has turned a small internal fault into a total outage. That is
 * the right call for a limiter defending against abuse and the wrong one for a
 * limiter enforcing a paid quota; this is the former.
 *
 * <p><b>One instance.</b> Buckets are in-process, which is complete while this
 * runs as a single JVM and is not the day there are two: each replica would
 * allow the full quota. That is the same trigger as
 * {@code docs/CACHING_STRATEGY.md} § 7, and Phase 08 § 1.2 has the Redis-backed
 * replacement ready for it.
 */
@Component
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

    /** Login bodies are small; anything larger is not a login and is not buffered. */
    private static final int MAX_BUFFERED_BODY = 8 * 1024;

    private final com.github.benmanes.caffeine.cache.Cache<String, Bucket> buckets;
    private final RateLimitKeyResolver keyResolver;
    private final RateLimitTierResolver tierResolver;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public RateLimitingFilter(RateLimitKeyResolver keyResolver,
                              RateLimitTierResolver tierResolver,
                              MeterRegistry meterRegistry,
                              ObjectMapper objectMapper,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${rateLimit.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.keyResolver = keyResolver;
        this.tierResolver = tierResolver;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;

        // Bounded and expiring, which is the whole fix for the leak. Eviction is
        // by last *access*: a key nobody has touched for an hour cannot be
        // holding a quota worth remembering, since the longest bandwidth here
        // refills within one.
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(1))
                .maximumSize(100_000)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, buckets, "rateLimit:buckets");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Off under the `test` profile, and the reason is worth stating: the
        // integration tests sign in repeatedly, and AUTHENTICATION deliberately
        // allows five attempts a minute. With limiting on they spent 32 requests
        // being throttled, one class took six minutes, and the run failed with
        // connection-starvation errors that read exactly like a flaky link. The
        // filter stays in the chain either way, so
        // SecurityFilterOrderTest still proves it is wired and where; the limits
        // themselves are covered by RateLimitingFilterTest, which builds the
        // filter directly and is unaffected by this flag.
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitTier tier = tierResolver.resolve(request);

        // Only a login needs its body read, and only to charge the attempt to the
        // account being attacked as well as the address it came from.
        HttpServletRequest downstream = request;
        String email = null;
        if (tier == RateLimitTier.AUTHENTICATION) {
            CachedBodyRequest cached = CachedBodyRequest.wrap(request, MAX_BUFFERED_BODY);
            if (cached != null) {
                downstream = cached;
                email = readEmail(cached.body());
            }
        }

        ConsumptionProbe probe;
        try {
            probe = consume(keyResolver.resolve(request, tier, email), tier);
        } catch (Exception storeFailed) {
            // Fail open. See the class note: a broken limiter must not become an
            // outage of everything it was protecting.
            log.error("Rate limiter unavailable, letting the request through", storeFailed);
            meterRegistry.counter("rate_limit.failures").increment();
            filterChain.doFilter(downstream, response);
            return;
        }

        // Always, including on success. A client that is never told where it
        // stands cannot back off before it is rejected.
        response.setHeader("X-RateLimit-Limit", String.valueOf(tier.burstCapacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, probe.getRemainingTokens())));

        if (probe.isConsumed()) {
            filterChain.doFilter(downstream, response);
            return;
        }

        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));

        meterRegistry.counter("rate_limit.rejected", "tier", tier.name()).increment();
        // The key is deliberately absent from this line: for a login it contains
        // the email of whoever is being attacked, and logs are not the place for
        // it.
        log.warn("Rate limit exceeded: tier={} method={} path={}",
                tier, request.getMethod(), request.getRequestURI());

        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "status", 429,
                "error", "RATE_LIMITED",
                "message", "Too many requests. Please retry in " + retryAfter + " seconds.",
                "retryAfterSeconds", retryAfter));
    }

    /**
     * Charges the request to every bucket that applies, and reports the tightest.
     *
     * <p>A login is charged to both its email and its address. Consuming from
     * only one of them — whichever was available — would leave the other attack
     * unmeasured, which is the shape the two keys exist to cover between them.
     */
    private ConsumptionProbe consume(List<String> keys, RateLimitTier tier) {
        ConsumptionProbe tightest = null;
        for (String key : keys) {
            Bucket bucket = buckets.get(key, k -> Bucket.builder()
                    .addLimit(tier.configuration().getBandwidths()[0])
                    .addLimit(tier.configuration().getBandwidths()[1])
                    .build());
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (tightest == null
                    || !probe.isConsumed()
                    || probe.getRemainingTokens() < tightest.getRemainingTokens()) {
                tightest = probe;
            }
        }
        return tightest;
    }

    private String readEmail(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode email = node.get("email");
            return email == null || email.isNull() ? null : email.asText();
        } catch (Exception notJson) {
            // A malformed body is the endpoint's problem to report, not this
            // filter's. Fall back to limiting by address alone.
            return null;
        }
    }
}
