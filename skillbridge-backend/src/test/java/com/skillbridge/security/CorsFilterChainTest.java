package com.skillbridge.security;

import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS as a browser meets it: through the real security filter chain.
 *
 * <p>The context runs with an origin that appears nowhere in the code, so a
 * pass here means the policy came from {@code app.cors.allowed-origins}. Before
 * 2026-09-19 it did not: {@code SecurityConfig} hardcoded the two localhost
 * origins, and the first two tests below fail against that version.
 */
@SpringBootTest(properties = "app.cors.allowed-origins=" + CorsFilterChainTest.ALLOWED)
@AutoConfigureMockMvc
@IntegrationTest
class CorsFilterChainTest {

    static final String ALLOWED = "https://demo.skillbridge.test";
    private static final String EVIL = "https://evil.example";

    @Autowired private MockMvc mvc;

    private ResultActions preflight(String origin, String path) throws Exception {
        return mvc.perform(options(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"));
    }

    @Test
    @DisplayName("a preflight from the configured origin is allowed, with credentials")
    void configuredOriginAllowed() throws Exception {
        preflight(ALLOWED, "/api/v1/admin/batches")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("an origin that is not configured is refused, even the old hardcoded localhost")
    void unconfiguredOriginRefused() throws Exception {
        preflight("http://localhost:5173", "/api/v1/admin/batches")
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("a foreign origin is refused on a path whose controller once said @CrossOrigin(\"*\")")
    void formerWildcardControllerRefusesForeignOrigin() throws Exception {
        // AdminEnrollmentController carried origins = "*". This passed before its
        // removal too -- the filter answers the preflight before the controller is
        // consulted. CorsPolicyTest is what keeps the annotations out.
        preflight(EVIL, "/api/v1/admin/batches/1/enrollments")
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("a simple request from a foreign origin is refused, on a public endpoint too")
    void foreignSimpleRequestRefused() throws Exception {
        mvc.perform(get("/api/v1/colleges/active").header(HttpHeaders.ORIGIN, EVIL))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
