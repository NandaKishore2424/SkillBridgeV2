package com.skillbridge.common.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Makes the request body readable twice and the response body readable at all.
 *
 * <p>{@link IdempotencyInterceptor} needs both and can have neither on its own:
 * a servlet request body is a one-shot stream, so hashing it in
 * {@code preHandle} would leave nothing for the handler to bind; and a response
 * that has been written to the client cannot be read back in
 * {@code afterCompletion} to be stored.
 *
 * <p>Only wraps requests that carry an {@code Idempotency-Key} header, so no
 * other request pays for the buffering.
 *
 * <p><b>Multipart is skipped deliberately.</b> The servlet container parses
 * multipart bodies from its own stream, so buffering underneath it is not
 * something to do quietly — a file upload would either break or be silently
 * read into memory whole. {@code @Idempotent} is therefore a JSON-body
 * mechanism, and {@code IdempotencyRulesTest} fails the build if it is put on a
 * multipart handler rather than leaving that to be discovered at runtime.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IdempotencyKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getHeader(HEADER) == null) {
            return true;
        }
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        CachedBodyRequest cachedRequest = new CachedBodyRequest(request);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cachedRequest, cachedResponse);
        } finally {
            // Without this the client receives an empty body: everything the
            // handler wrote is sitting in the wrapper's buffer, not the socket.
            cachedResponse.copyBodyToResponse();
        }
    }

    /** A request whose body is read once into memory and served from there after. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = StreamUtils.copyToByteArray(request.getInputStream());
        }

        byte[] body() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return source.read(); }
                @Override public boolean isFinished() { return source.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
