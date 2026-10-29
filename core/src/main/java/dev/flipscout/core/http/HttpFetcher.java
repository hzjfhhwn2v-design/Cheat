package dev.flipscout.core.http;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/** Minimal HTTP GET abstraction so the APIs can be tested without a network. */
public interface HttpFetcher {
    Response get(String url) throws IOException;

    final class Response implements Closeable {
        public final int status;
        /** Response body, already decompressed. */
        public final InputStream body;
        /** Date header in epoch millis, or 0 when missing. */
        public final long serverDate;
        /** Retry-After in milliseconds, or 0 when missing. */
        public final long retryAfterMs;

        public Response(int status, InputStream body, long serverDate, long retryAfterMs) {
            this.status = status;
            this.body = body;
            this.serverDate = serverDate;
            this.retryAfterMs = retryAfterMs;
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }
}
