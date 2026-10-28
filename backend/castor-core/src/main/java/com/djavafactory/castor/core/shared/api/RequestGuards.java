package com.djavafactory.castor.core.shared.api;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;

import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * Size and media type rules for request bodies: 1 MB everywhere, 10 MB for the GeoJSON import, and only JSON or GeoJSON
 * on write methods. The Content-Length check rejects early; the reader interceptor also covers chunked bodies.
 */
public class RequestGuards {

    public static final long DEFAULT_LIMIT = 1024 * 1024;
    public static final long IMPORT_LIMIT = 10 * 1024 * 1024;
    public static final MediaType GEO_JSON = new MediaType("application", "geo+json");

    private static final String LIMIT_PROPERTY = "castor.bodyLimit";
    private static final Pattern IMPORT_PATH = Pattern.compile("^/api/v1/layouts/[^/]+/import/?$");
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH");

    @ServerRequestFilter(preMatching = true, priority = 10)
    public void guard(ContainerRequestContext request) {
        long limit = IMPORT_PATH
                        .matcher(request.getUriInfo().getRequestUri().getRawPath())
                        .matches()
                ? IMPORT_LIMIT
                : DEFAULT_LIMIT;
        request.setProperty(LIMIT_PROPERTY, limit);

        long length = declaredLength(request);
        boolean chunked = "chunked".equalsIgnoreCase(request.getHeaderString("Transfer-Encoding"));
        if (length > limit) {
            throw new ClientErrorException(Response.Status.REQUEST_ENTITY_TOO_LARGE);
        }
        if ((length > 0 || chunked) && WRITE_METHODS.contains(request.getMethod()) && !isAllowedType(request)) {
            throw new NotSupportedException();
        }
    }

    private static boolean importPath(ContainerRequestContext request) {
        return IMPORT_PATH
                .matcher(request.getUriInfo().getRequestUri().getRawPath())
                .matches();
    }

    private static long declaredLength(ContainerRequestContext request) {
        String header = request.getHeaderString("Content-Length");
        if (header == null) {
            return 0;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isAllowedType(ContainerRequestContext request) {
        MediaType type = request.getMediaType();
        return type != null
                && (type.isCompatible(MediaType.APPLICATION_JSON_TYPE)
                        || type.isCompatible(GEO_JSON)
                        || (type.isCompatible(MediaType.MULTIPART_FORM_DATA_TYPE) && importPath(request)));
    }

    /** Counts bytes as they are read so a chunked body cannot slip past the Content-Length check. */
    @Provider
    public static class BodyLimitReaderInterceptor implements ReaderInterceptor {

        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
            if (context.getProperty(LIMIT_PROPERTY) instanceof Long limit) {
                context.setInputStream(new LimitedInputStream(context.getInputStream(), limit));
            }
            try {
                return context.proceed();
            } catch (IOException e) {
                // Parsers wrap the exception thrown by the limited stream; surface it as the 413 it is
                for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                    if (cause instanceof ClientErrorException tooLarge) {
                        throw tooLarge;
                    }
                }
                throw e;
            }
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                consume(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                consume(count);
            }
            return count;
        }

        private void consume(long count) {
            remaining -= count;
            if (remaining < 0) {
                throw new ClientErrorException(Response.Status.REQUEST_ENTITY_TOO_LARGE);
            }
        }
    }
}
