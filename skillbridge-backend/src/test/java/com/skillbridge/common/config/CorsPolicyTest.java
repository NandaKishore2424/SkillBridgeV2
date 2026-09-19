package com.skillbridge.common.config;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One CORS policy, from configuration.
 *
 * <p>Until 2026-09-19 there were sixteen: {@code SecurityConfig} hardcoded two
 * localhost origins and ignored {@code app.cors.allowed-origins}, and fifteen
 * controllers carried their own {@code @CrossOrigin}, three of them
 * {@code "*"}. The filter chain's policy was the one that actually applied, so
 * the annotations were inert -- and the one setting an operator could change
 * did nothing. {@code CorsFilterChainTest} covers the policy as the browser
 * sees it; this covers the two ways it can quietly fork again.
 */
class CorsPolicyTest {

    @Test
    @DisplayName("no controller or handler declares its own @CrossOrigin")
    void noCrossOriginAnnotations() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.skillbridge");

        List<String> violations = production.stream()
                .flatMap(c -> Stream.concat(
                        Stream.of(c).filter(x -> x.isAnnotatedWith(CrossOrigin.class)).map(x -> x.getName()),
                        c.getMethods().stream().filter(m -> m.isAnnotatedWith(CrossOrigin.class))
                                .map(m -> m.getFullName())))
                .toList();

        assertThat(violations)
                .withFailMessage("""
                        @CrossOrigin found:

                        %s

                        CORS is configured once, in SecurityConfig, from \
                        app.cors.allowed-origins. The security filter rejects a \
                        disallowed origin before a controller is reached, so this \
                        annotation does not do what it says.""",
                        String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("configured origins are trimmed and used exactly")
    void usesConfiguredOrigins() {
        CorsConfiguration config = configFor(List.of(" https://a.example ", "https://b.example"));

        assertThat(config.getAllowedOrigins()).containsExactly("https://a.example", "https://b.example");
        assertThat(config.getAllowCredentials()).isTrue();
    }

    @Test
    @DisplayName("a wildcard origin fails at startup, not as a 500 on the first request")
    void wildcardRejected() {
        assertThatThrownBy(() -> SecurityConfig.corsConfigurationSourceFor(List.of("*")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
        assertThatThrownBy(() -> SecurityConfig.corsConfigurationSourceFor(List.of("https://*.example")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an empty origin list fails at startup")
    void emptyRejected() {
        assertThatThrownBy(() -> SecurityConfig.corsConfigurationSourceFor(List.of(" ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    private static CorsConfiguration configFor(List<String> origins) {
        return SecurityConfig.corsConfigurationSourceFor(origins)
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/anything"));
    }
}
