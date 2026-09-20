package com.skillbridge.common.config;

import com.skillbridge.auth.AuthProperties;
import com.skillbridge.auth.JwtProperties;
import com.skillbridge.bulkupload.importer.ImportProperties;
import com.skillbridge.common.audit.AuditProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the typed settings.
 *
 * <p>Listed here rather than scanned: this file is the index of what the
 * application can be configured with, and an unused record is visible as a line
 * nobody reads. {@code app.mail} is registered by {@code MailConfig}, next to
 * the gateway it chooses.
 *
 * <p>Each record is {@code @Validated}, so a missing secret or a zero TTL stops
 * startup with the property name, instead of a NullPointerException or a
 * silently useless value on the first request that needs it.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, AuthProperties.class, ImportProperties.class,
        AuditProperties.class, CorsProperties.class})
public class TypedConfiguration {
}
