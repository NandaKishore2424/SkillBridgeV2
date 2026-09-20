package com.skillbridge.common.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * {@code app.cors.*}.
 *
 * @param allowedOrigins exact origins, comma-separated in the environment. Not a
 *                       wildcard: credentials are allowed, and SecurityConfig
 *                       refuses to start with one
 */
@Validated
@ConfigurationProperties("app.cors")
public record CorsProperties(@NotEmpty List<String> allowedOrigins) {
}
