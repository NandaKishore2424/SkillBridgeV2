package com.skillbridge.auth.filter;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.stream.Collectors;

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
            // For simple tokens: "token_{userId}_{timestamp}"
            if (token.startsWith("token_")) {
                String[] parts = token.split("_");
                if (parts.length >= 2) {
                    Long userId = Long.parseLong(parts[1]);
                    
                    User user = userRepository.findById(userId)
                        .orElse(null);
                    
                    if (user != null && user.getIsActive()) {
                        // Extract roles
                        var authorities = user.getRoles().stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                            .collect(Collectors.toList());
                        
                        UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                user,
                                null,
                                authorities
                            );
                        
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        
                        log.debug("Authenticated user: {} with roles: {}", user.getEmail(), authorities);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to authenticate token: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}

