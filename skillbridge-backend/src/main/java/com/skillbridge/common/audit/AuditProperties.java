package com.skillbridge.common.audit;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code app.audit.*}: the audit trail's own connection pool.
 *
 * <p>Small and impatient on purpose. {@link AuditLogWriter} explains why the
 * trail does not share the main pool; {@link AuditLogService} why a write that
 * cannot be made is dropped rather than failing the request.
 *
 * @param poolSize          connections for audit writes
 * @param connectionTimeout how long an audit write waits for one
 */
@Validated
@ConfigurationProperties("app.audit")
public record AuditProperties(@Positive int poolSize, @NotNull Duration connectionTimeout) {
}
