package com.skillbridge.common.throttle;

import com.skillbridge.auth.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * What a rate-limit bucket is keyed on.
 *
 * <p>Two bugs in the previous implementation lived here, and both made the
 * limiter decorative rather than merely imprecise.
 *
 * <p><b>It keyed on the access token.</b> Tokens rotate every fifteen minutes,
 * so a user got a brand-new bucket four times an hour just by using the
 * application normally — the limit reset itself, and the map of buckets grew
 * forever because the old keys could never be hit again. Keying on the user id
 * fixes the reset and the leak together.
 *
 * <p><b>It read {@code X-Forwarded-For} from the left.</b> That header is a
 * comma-separated chain the client can seed, so
 * {@code X-Forwarded-For: 1.2.3.4} bought a fresh bucket per forged value, and
 * a new one for every request. The entries that can be trusted are the ones our
 * own proxies appended, which are at the <em>right</em>-hand end — so this counts
 * back from the right by the number of proxies we know are in front. With none
 * in front, which is the case today, the header is ignored entirely.
 */
@Component
@Slf4j
public class RateLimitKeyResolver {

    /**
     * How many proxies of ours sit in front of this application.
     *
     * <p>Zero, and deliberately so: nothing is deployed behind a load balancer
     * yet, so every value in {@code X-Forwarded-For} is client-supplied and none
     * of it is evidence. Raise this to the real hop count the day something is
     * put in front, and not before — a count larger than the truth reads an
     * attacker-controlled entry as the client address.
     */
    private final int trustedProxyCount;

    public RateLimitKeyResolver(@Value("${app.trustedProxyCount:0}") int trustedProxyCount) {
        this.trustedProxyCount = trustedProxyCount;
    }

    /**
     * The buckets a request has to consume from — usually one, two for a login.
     *
     * <p>A login is charged to the email <em>and</em> the address, because the two
     * catch different attacks and neither catches the other's: one password tried
     * across a thousand accounts from one address is invisible to a per-email
     * limit, and one account attacked from a thousand addresses is invisible to a
     * per-address one. Returning whichever key happens to be available would
     * leave one of those open.
     */
    public List<String> resolve(HttpServletRequest request, RateLimitTier tier, String email) {
        Long userId = currentUserId();
        if (userId != null) {
            return List.of("rl:%s:u:%d".formatted(tier, userId));
        }

        String ipKey = "rl:%s:ip:%s".formatted(tier, clientIp(request));
        if (tier == RateLimitTier.AUTHENTICATION && email != null && !email.isBlank()) {
            return List.of("rl:AUTH:email:" + email.toLowerCase(), ipKey);
        }
        return List.of(ipKey);
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user.getId();
        }
        return null;
    }

    /**
     * The client address, counting back from the right of the forwarded chain.
     *
     * <p>With {@code trustedProxyCount} at zero the header is not consulted at
     * all, which is the only correct reading when nothing of ours appended to it.
     */
    String clientIp(HttpServletRequest request) {
        if (trustedProxyCount <= 0) {
            return request.getRemoteAddr();
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }

        String[] hops = forwarded.split(",");
        // The rightmost entry was added by the proxy nearest us. Anything the
        // client sent arrives further left, so step back only as far as we have
        // proxies to vouch for.
        int index = Math.max(0, hops.length - trustedProxyCount);
        String candidate = hops[index].trim();
        return candidate.isEmpty() ? request.getRemoteAddr() : candidate;
    }
}
