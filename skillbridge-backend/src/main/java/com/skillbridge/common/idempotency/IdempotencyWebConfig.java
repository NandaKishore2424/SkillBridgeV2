package com.skillbridge.common.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link IdempotencyInterceptor} across the API.
 *
 * <p>Registered for every path on purpose. The interceptor itself is a no-op
 * unless the handler carries {@link Idempotent}, and pinning a path list here
 * would mean a new annotated endpoint silently going unprotected because
 * somebody forgot to add its prefix — the annotation is the switch.
 */
@Configuration
@RequiredArgsConstructor
public class IdempotencyWebConfig implements WebMvcConfigurer {

    private final IdempotencyInterceptor idempotencyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/**");
    }
}
