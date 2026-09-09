package com.skillbridge.auth.filter;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.Collection;
import io.jsonwebtoken.Claims;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.service.JwtService;
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

/**
 * Token Authentication Filter
 * 
 * Validates tokens from Authorization header and sets authentication in security context.
 * For simple tokens (format: "token_{userId}_{timestamp}"), extracts userId and loads user.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final JwtService jwtService;

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

        String token = authHeader.substring(7); // Remove "Bearer " prefix
        
        try {
            if (!jwtService.isTokenValid(token)) {
                filterChain.doFilter(request, response);
                return;
            }

            AuthenticatedUser principal = principalFrom(token);

            if (principal != null && principal.isActive()) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                principal.getAuthorities()
                        );

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.debug("Authenticated user {} with roles {}", principal.getEmail(), principal.getRoles());
            }
        } catch (Exception e) {
            log.warn("Failed to authenticate token: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
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
     * as the token, so that now takes up to one access-token lifetime — which is
     * why the TTL was cut from an hour to fifteen minutes in the same change.
     * The refresh path still re-reads the user and refuses an inactive one, so a
     * deactivated user cannot extend past their current token; the window is
     * bounded by the TTL, not open-ended.
     *
     * <p><b>The fallback is a migration path, not a safety net.</b> A token
     * issued before this change has no {@code roles} or
     * {@code mustChangePassword} claim. Defaulting those would be silently
     * wrong in the dangerous direction — a missing {@code mustChangePassword}
     * reads as false, which is exactly the bypass that flag was added to close —
     * so such a token takes the old database path instead. Every token reissues
     * within one TTL, after which this branch stops being reached.
     */
    @SuppressWarnings("unchecked")
    private AuthenticatedUser principalFrom(String token) {
        Claims claims = jwtService.claims(token);
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

