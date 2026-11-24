package com.telecom.support.common.web;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.lang.NonNull;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Enforces {@code app.web.max-request-size} (default 1 MB) on {@code @RequestBody} payloads (00-overview §4, 413).
 * A declared {@code Content-Length} over the limit is rejected before reading; a body without one is cut off once it
 * passes the limit. Either way {@link PayloadTooLargeException} is raised and mapped to 413 {@code payload-too-large}.
 */
@ControllerAdvice
public class RequestSizeLimitAdvice extends RequestBodyAdviceAdapter {

    /** The request body is larger than {@code app.web.max-request-size}. */
    public static class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException() {
            super("Request body exceeds the configured maximum size");
        }
    }

    private final long maxBytes;

    public RequestSizeLimitAdvice(@Value("${app.web.max-request-size:1MB}") DataSize maxRequestSize) {
        this.maxBytes = maxRequestSize.toBytes();
    }

    @Override
    public boolean supports(@NonNull MethodParameter methodParameter, @NonNull Type targetType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    @NonNull
    public HttpInputMessage beforeBodyRead(@NonNull HttpInputMessage inputMessage, @NonNull MethodParameter parameter,
                                           @NonNull Type targetType,
                                           @NonNull Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        if (inputMessage.getHeaders().getContentLength() > maxBytes) {
            throw new PayloadTooLargeException();
        }
        InputStream limited = new LimitedInputStream(inputMessage.getBody(), maxBytes);
        return new HttpInputMessage() {
            @Override
            @NonNull
            public InputStream getBody() {
                return limited;
            }

            @Override
            @NonNull
            public HttpHeaders getHeaders() {
                return inputMessage.getHeaders();
            }
        };
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long max;
        private long count;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(@NonNull byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        private void add(long n) {
            count += n;
            if (count > max) {
                throw new PayloadTooLargeException();
            }
        }
    }
}
