package com.skillbridge.common.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Emits deprecation headers for endpoints marked {@link DeprecatedEndpoint}.
 *
 * <p>Sends three headers:
 *
 * <ul>
 *   <li>{@code Deprecation: true} — RFC 8594.</li>
 *   <li>{@code Sunset: <HTTP-date>} — RFC 8594 requires an IMF-fixdate, not the
 *       ISO date the annotation carries, so it is converted here. Getting this
 *       wrong produces a header that looks right and that no client library
 *       parses.</li>
 *   <li>{@code Link: <replacement>; rel="successor-version"} — RFC 8288, so the
 *       response says where to go rather than only that the door is closing.</li>
 * </ul>
 *
 * <p>{@code supports} returns true for every response and the annotation is
 * checked per call. Narrowing it to a converter type would silently skip
 * endpoints returning {@code ResponseEntity<Void>}, which is exactly the shape a
 * deprecated DELETE tends to have.
 */
@RestControllerAdvice
public class DeprecationHeaderAdvice implements ResponseBodyAdvice<Object> {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME;

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {

        DeprecatedEndpoint marker = returnType.getMethodAnnotation(DeprecatedEndpoint.class);
        if (marker == null) {
            return body;
        }

        response.getHeaders().add("Deprecation", "true");
        response.getHeaders().add("Sunset", toHttpDate(marker.sunset()));
        response.getHeaders().add("Link",
                "<" + marker.replacement() + ">; rel=\"successor-version\"");

        return body;
    }

    /** {@code 2026-12-31} to {@code Thu, 31 Dec 2026 00:00:00 GMT}. */
    private static String toHttpDate(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).format(HTTP_DATE);
    }
}
