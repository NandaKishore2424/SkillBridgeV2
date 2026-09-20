package com.skillbridge.security;

import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Every endpoint, called with no credentials at all.
 *
 * <p>{@code EndpointRolesTest} says which roles an endpoint admits; this asks
 * the blunter question underneath it -- can a stranger reach it? Each mapped
 * route is called without a token and must answer 401, unless it is one of the
 * few deliberately public ones listed below.
 *
 * <p>The security filter chain runs before the controller, so a protected
 * endpoint answers 401 whatever the body is. That is what makes "exactly 401"
 * a fair expectation for a request with no body.
 *
 * <p>It found {@code /v3/api-docs}: the complete map of the API, every route
 * and every schema, readable by anyone, while the Swagger UI that only renders
 * it was gated behind a flag and {@code /actuator/metrics} was admin-only for
 * exactly this reason.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTest
class UnauthenticatedAccessTest {

    /**
     * Reachable without signing in, each for a reason:
     *
     * <ul>
     *   <li>the auth endpoints, which is how one signs in (and where the
     *       rate limiter's AUTHENTICATION tier applies);</li>
     *   <li>{@code /colleges/active}, which the registration screen needs before
     *       anyone has an account;</li>
     *   <li>the two probe endpoints a load balancer calls.</li>
     * </ul>
     *
     * <p>{@code /auth/me} and {@code /auth/change-password} sit under the same
     * URL prefix but refuse a stranger themselves, so they are not listed here:
     * the test expects their 401 like any other.
     */
    private static final Set<String> PUBLIC = Set.of(
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/first-login",
            "GET /api/v1/colleges/active",
            "GET /actuator/health",
            "GET /actuator/info");

    @Autowired private MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("a caller with no token gets 401 from every endpoint but the public few")
    void strangersAreRefused() throws Exception {
        List<String> reachable = new ArrayList<>();
        Set<String> checked = new TreeSet<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                // Path variables are irrelevant here: authentication is decided
                // before anything looks at them, so any value will do.
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                for (HttpMethod method : methods(info)) {
                    String call = method.name() + " " + pattern;
                    if (PUBLIC.contains(method.name() + " " + pattern)) {
                        continue;
                    }
                    checked.add(call);
                    int status = mvc.perform(request(method, path)).andReturn().getResponse().getStatus();
                    if (status != 401) {
                        reachable.add("%-6s %-60s -> %d".formatted(method.name(), pattern, status));
                    }
                }
            }
        }

        assertThat(checked).as("a run that calls nothing proves nothing").hasSizeGreaterThan(100);
        assertThat(reachable)
                .withFailMessage("""
                        These answered a caller with no credentials with something other \
                        than 401:

                        %s

                        Either the endpoint is meant to be public -- add it to PUBLIC here, \
                        with the reason -- or its rule in SecurityConfig is missing.""",
                        String.join("\n", reachable))
                .isEmpty();
    }

    /** A mapping with no method condition answers every method. */
    private static List<HttpMethod> methods(RequestMappingInfo info) {
        if (info.getMethodsCondition().getMethods().isEmpty()) {
            return List.of(HttpMethod.GET);
        }
        return info.getMethodsCondition().getMethods().stream()
                .map(m -> HttpMethod.valueOf(m.name()))
                .toList();
    }
}
