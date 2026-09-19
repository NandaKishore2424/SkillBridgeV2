package com.skillbridge.auth.filter;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.JsonSecurityErrorHandler;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.auth.service.TokenRevocationService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authenticates a request from the JWT in its {@code Authorization: Bearer}
 * header.
 *
 * <p>A request with no bearer token passes through anonymously; the
 * authorization rules decide whether that is allowed. A request whose token is
 * presented but not accepted (bad signature, expired, revoked, inactive account)
 * also passes through anonymously, marked with
 * {@link JsonSecurityErrorHandler#REJECTED_TOKEN_ATTRIBUTE} so that, if the
 * endpoint needs authentication, the 401 says {@code invalid_token}. The filter
 * never writes a response itself: refusing is the entry point's job, and a
 * public endpoint must still work for a caller holding a stale token.
 *
 * <p>The token is verified <b>once</b> per request. It used to be parsed three
 * times (validity, principal, issued-at), three HMAC verifications for the
 * same answer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocation;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        try {
            // Parsing is the verification: signature, algorithm and expiry are
            // all checked here, and it throws on any of them.
            Claims claims = jwtService.claims(token);
            AuthenticatedUser principal = principalFrom(claims);

            // Deactivation has to bite now, not when the token expires. The
            // claims say isActive because they were true when the token was
            // issued; this asks whether the account has been deactivated since.
            // An in-process lookup, so it adds no statement and no connection --
            // which is the whole reason the principal is built from claims.
            if (principal != null && tokenRevocation.isRevoked(principal.getId(), issuedAt(claims))) {
                log.info("Refused a token issued before user {}'s sessions were revoked", principal.getId());
                markRejected(request);
            } else if (principal != null && principal.isActive()) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("Authenticated user {} with roles {}", principal.getEmail(), principal.getRoles());
            } else {
                markRejected(request);
            }
        } catch (Exception e) {
            // Expired, tampered, malformed: all mean the same thing to a caller,
            // and distinguishing them in a response tells an attacker which part
            // of a forgery to fix.
            log.debug("Rejected a bearer token: {}", e.getMessage());
            markRejected(request);
        }

        filterChain.doFilter(request, response);
    }

    private static void markRejected(HttpServletRequest request) {
        request.setAttribute(JsonSecurityErrorHandler.REJECTED_TOKEN_ATTRIBUTE, Boolean.TRUE);
    }

    /** The token's {@code iat}, or null if it carries none. */
    private static Instant issuedAt(Claims claims) {
        Date issued = claims.getIssuedAt();
        return issued == null ? null : issued.toInstant();
    }

    /**
     * Builds the principal from the token's claims, falling back to the database
     * only for tokens issued before the claims existed.
     *
     * <p>This used to load the user on every authenticated request — every page,
     * every poll — to read {@code isActive} and the roles. Against a database in
     * another region that is a round trip of roughly 150 ms under every endpoint
     * the application serves, and it was one of the two connection checkouts a
     * request made.
     *
     * <p><b>What was traded for it.</b> The database read meant a deactivated
     * user lost access on their very next request. The claims are only as fresh
     * as the token, so revocation is handled separately by
     * {@link TokenRevocationService}, and the TTL was cut from an hour to fifteen
     * minutes in the same change. The refresh path still re-reads the user and
     * refuses an inactive one.
     *
     * <p><b>The fallback is a migration path, not a safety net.</b> A token
     * issued before this change has no {@code roles} or
     * {@code mustChangePassword} claim. Defaulting those would be silently
     * wrong in the dangerous direction — a missing {@code mustChangePassword}
     * reads as false, which is exactly the bypass that flag was added to close —
     * so such a token takes the old database path instead.
     */
    private AuthenticatedUser principalFrom(Claims claims) {
        Long userId = Long.valueOf(claims.getSubject());

        Object rawRoles = claims.get("roles");
        Object rawMustChange = claims.get("mustChangePassword");

        if (rawRoles instanceof Collection<?> roleList && !roleList.isEmpty()
                && rawMustChange instanceof Boolean mustChange) {
            Set<String> roles = roleList.stream().map(String::valueOf)
                    .collect(Collectors.toUnmodifiableSet());
            Number collegeId = claims.get("collegeId", Number.class);
            return new AuthenticatedUser(
                    userId,
                    claims.get("email", String.class),
                    collegeId == null ? null : collegeId.longValue(),
                    Boolean.TRUE.equals(claims.get("isActive", Boolean.class)),
                    mustChange,
                    roles);
        }

        log.debug("Token for user {} predates the roles/mustChangePassword claims; "
                + "falling back to a database lookup for this request", userId);
        User user = userRepository.findById(userId).orElse(null);
        return user == null ? null : new AuthenticatedUser(user);
    }
}
