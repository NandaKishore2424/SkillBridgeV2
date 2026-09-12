package com.skillbridge.common.observability;

import com.skillbridge.auth.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds who the request is from to the logging context.
 *
 * <p>{@link CorrelationIdFilter} answers "which request"; this answers "whose".
 * Together they turn a support conversation from "it broke yesterday afternoon"
 * into one query.
 *
 * <p><b>It has to run inside the security chain, after authentication.</b> That
 * is not a preference: before the authentication filter there is no principal,
 * so this would read an empty context and add nothing, silently. Spring Boot
 * auto-registers every {@code Filter} bean into the servlet chain as well, and
 * that copy would run at the wrong moment — so this one is registered
 * explicitly by {@code SecurityConfig} and excluded from auto-registration in
 * {@code FilterRegistrationConfig}, which makes the position real rather than
 * incidental.
 *
 * <p><b>It does not clear the MDC.</b> {@code CorrelationIdFilter} wraps this
 * one and clears everything in its {@code finally}, so clearing here would only
 * blind the outer filter's own logging on the way back out. One owner for the
 * lifecycle, and it is the outermost filter.
 */
@Component
public class UserContextLogFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            MDC.put(CorrelationIdFilter.MDC_USER, String.valueOf(user.getId()));
            if (user.getCollegeId() != null) {
                MDC.put(CorrelationIdFilter.MDC_TENANT, String.valueOf(user.getCollegeId()));
            }
        }

        filterChain.doFilter(request, response);
    }
}
