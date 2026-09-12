package com.skillbridge.common.throttle;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body can be read twice.
 *
 * <p>A servlet body is a one-shot stream, so a filter that reads it consumes it
 * and the controller receives nothing. {@link RateLimitingFilter} needs the email
 * from a login body to charge the attempt to the account being attacked, so the
 * bytes are buffered here and replayed to whatever reads them next.
 *
 * <p><b>Bounded, and only on the login path.</b> Buffering an unbounded body in a
 * filter is a way to turn one large upload into heap pressure, so anything over
 * the cap is declined — {@link #wrap} returns null and the caller limits by
 * address alone. A login body that does not fit in eight kilobytes is not a
 * login.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    /** @return null when the body is absent, too large, or unreadable. */
    static CachedBodyRequest wrap(HttpServletRequest request, int maxBytes) {
        int declared = request.getContentLength();
        if (declared == 0 || declared > maxBytes) {
            return null;
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(maxBytes + 1);
            if (bytes.length == 0 || bytes.length > maxBytes) {
                return null;
            }
            return new CachedBodyRequest(request, bytes);
        } catch (IOException unreadable) {
            return null;
        }
    }

    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream replay = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return replay.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("This body is already buffered");
            }

            @Override
            public int read() {
                return replay.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
