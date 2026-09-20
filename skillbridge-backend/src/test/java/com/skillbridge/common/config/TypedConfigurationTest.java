package com.skillbridge.common.config;

import com.skillbridge.auth.AuthProperties;
import com.skillbridge.auth.JwtProperties;
import com.skillbridge.bulkupload.importer.ImportProperties;
import com.skillbridge.common.audit.AuditProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settings are bound once, typed, and checked at startup.
 *
 * <p>They used to be {@code @Value} strings read where they were needed, which
 * let two readers of one setting disagree: {@code JwtService} defaulted the
 * access TTL to 3600 while {@code application.yaml} said 900, so a missing
 * property quietly issued hour-long tokens -- and that TTL is how long a
 * changed role takes to bite.
 *
 * <p>The second half is the point of {@code @Validated}: a setting that is
 * missing or nonsensical stops the application at startup, naming the property,
 * rather than failing on the first request that needs it.
 */
class TypedConfigurationTest {

    private static final String SECRET = "dGVzdC1vbmx5LWp3dC1zaWduaW5nLWtleS1ub3QtYS1yZWFsLXNlY3JldC0wMDAw";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TypedConfiguration.class)
            .withPropertyValues(
                    "jwt.secret=" + SECRET,
                    "jwt.accessTokenTtlSeconds=900",
                    "jwt.refreshTokenTtlSeconds=1209600",
                    "jwt.refreshReuseGraceSeconds=10",
                    "app.auth.refresh-cookie-secure=true",
                    "app.auth.invitation-ttl=7d",
                    "app.import.max-rows=2000",
                    "app.import.stale-after=10m",
                    "app.import.sweeper.enabled=true",
                    "app.audit.pool-size=2",
                    "app.audit.connection-timeout=1s",
                    "app.cors.allowed-origins=https://a.example,https://b.example");

    @Test
    @DisplayName("every setting binds, including durations and lists")
    void binds() {
        runner.run(context -> {
            assertThat(context.getBean(JwtProperties.class).accessTokenTtlSeconds()).isEqualTo(900);
            assertThat(context.getBean(JwtProperties.class).reuseGrace()).isEqualTo(Duration.ofSeconds(10));
            assertThat(context.getBean(AuthProperties.class).invitationTtl()).isEqualTo(Duration.ofDays(7));
            assertThat(context.getBean(AuthProperties.class).refreshCookieSecure()).isTrue();
            assertThat(context.getBean(ImportProperties.class).maxRows()).isEqualTo(2000);
            assertThat(context.getBean(ImportProperties.class).sweeper().enabled()).isTrue();
            assertThat(context.getBean(AuditProperties.class).connectionTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                    .containsExactly("https://a.example", "https://b.example");
        });
    }

    @Test
    @DisplayName("a missing JWT secret stops startup, naming the property")
    void secretIsRequired() {
        runner.withPropertyValues("jwt.secret=").run(context ->
                assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("jwt").hasMessageContaining("secret"));
    }

    @Test
    @DisplayName("a nonsensical value stops startup too")
    void valuesAreChecked() {
        runner.withPropertyValues("jwt.accessTokenTtlSeconds=0").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("accessTokenTtlSeconds"));
        runner.withPropertyValues("app.import.max-rows=-1").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("maxRows"));
        runner.withPropertyValues("app.cors.allowed-origins=").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("allowedOrigins"));
    }
}
