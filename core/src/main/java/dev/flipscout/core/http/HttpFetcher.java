package dev.flipscout.core.http;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/** Minimal HTTP abstraction so the APIs can be tested without a network. */
public interface HttpFetcher {
    Response get(String url) throws IOException;

    /** Sends {@code body} with POST (for Discord webhooks). */
    default Response post(String url, String contentType, byte[] body) throws IOException {
        throw new IOException("POST not supported");
    }

    final class Response implements Closeable {
        public final int status;
        /** Response body, already decompressed. */
        public final InputStream body;
        /** Date header in epoch millis, or 0 when missing. */
        public final long serverDate;
        /** Retry-After in milliseconds, or 0 when missing. */
        public final long retryAfterMs;
        private final Runnable disconnect;
        private boolean closed;

        public Response(int status, InputStream body, long serverDate, long retryAfterMs) {
            this(status, body, serverDate, retryAfterMs, null);
        }

        public Response(int status, InputStream body, long serverDate, long retryAfterMs, Runnable disconnect) {
            this.status = status;
            this.body = body;
            this.serverDate = serverDate;
            this.retryAfterMs = retryAfterMs;
            this.disconnect = disconnect;
        }

        /** Stops early: drops the connection instead of downloading the rest of the body. */
        public void abort() {
            if (closed) {
                return;
            }
            closed = true;
            if (disconnect != null) {
                disconnect.run();
            }
            try {
                body.close();
            } catch (IOException ignored) {
                // the connection is gone anyway
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            body.close();
        }
    }
}
