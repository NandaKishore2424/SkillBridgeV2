package com.skillbridge.common.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint for removal, and tells callers so in the response.
 *
 * <p>Deprecating by writing it in a changelog assumes someone reads the
 * changelog. {@link DeprecationHeaderAdvice} turns this annotation into RFC 8594
 * {@code Deprecation} and {@code Sunset} headers plus an RFC 8288 {@code Link}
 * to the replacement, so a client discovers it from the traffic it is already
 * making rather than from an outage on the sunset date.
 *
 * <p>Distinct from Java's {@code @Deprecated}, which is about compiling against
 * a symbol. This is about calling an HTTP endpoint, and nothing in the
 * compiler's world can see across that boundary.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8594">RFC 8594 — The Sunset HTTP Header Field</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DeprecatedEndpoint {

    /** ISO date the deprecation was announced, e.g. {@code "2026-09-06"}. */
    String since();

    /**
     * ISO date the endpoint may be removed. At least 90 days after
     * {@link #since()} — see {@code docs/API_VERSIONING.md}.
     */
    String sunset();

    /** Path a caller should move to, e.g. {@code "/api/v1/admin/students/{id}"}. */
    String replacement();

    /** Why, in one line. Surfaced in the OpenAPI description, not in a header. */
    String reason() default "";
}
