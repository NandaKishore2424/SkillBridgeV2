package com.skillbridge.auth.filter;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
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

            Long userId = jwtService.getUserId(token);
            // One statement, including the roles: Hibernate resolves the eager
            // @ManyToMany with a join for a single-entity load. Measured, because
            // "eager" does not guarantee it -- AuthPathQueryCostTest fails if this
            // ever becomes two, which on this path is ~150ms added to every
            // authenticated request.
            User user = userRepository.findById(userId).orElse(null);

            if (user != null && Boolean.TRUE.equals(user.getIsActive())) {
                // Wrap the entity in a detached UserDetails snapshot. Putting the
                // managed entity itself into the security context is what made
                // authentication.getName() return the whole object — password
                // hash included — instead of the email.
                AuthenticatedUser principal = new AuthenticatedUser(user);

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
}

