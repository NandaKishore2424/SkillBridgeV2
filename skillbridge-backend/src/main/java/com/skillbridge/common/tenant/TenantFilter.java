package com.skillbridge.common.tenant;

import com.skillbridge.auth.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class TenantFilter extends OncePerRequestFilter {

    private final EntityManager entityManager;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof User user) {
            boolean isSystemAdmin = user.getRoles().stream()
                    .anyMatch(role -> "SYSTEM_ADMIN".equals(role.getName()));

            if (!isSystemAdmin && user.getCollegeId() != null) {
                try {
                    Session session = entityManager.unwrap(Session.class);
                    session.enableFilter("collegeFilter")
                            .setParameter("collegeId", user.getCollegeId());
                } catch (Exception ex) {
                    log.warn("Failed to enable tenant filter: {}", ex.getMessage());
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
