package dev.flipscout.core.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/** {@link HttpFetcher} on top of HttpURLConnection (Java 8 has no java.net.http). */
public final class UrlFetcher implements HttpFetcher {
    private final String userAgent;
    private final SSLSocketFactory socketFactory;

    public UrlFetcher(String userAgent, SSLSocketFactory socketFactory) {
        this.userAgent = userAgent;
        this.socketFactory = socketFactory;
    }

    @Override
    public Response get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        if (socketFactory != null && connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(socketFactory);
        }
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        int status = connection.getResponseCode();
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (in == null) {
            in = new ByteArrayInputStream(new byte[0]);
        }
        if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
            in = new GZIPInputStream(in, 65536);
        }
        return new Response(status, maybeGunzip(in), connection.getDate(), retryAfter(connection));
    }

    /** Some sources serve .json.gz files as plain binary: unwrap those too. */
    static InputStream maybeGunzip(InputStream in) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in, 65536);
        buffered.mark(2);
        int b1 = buffered.read();
        int b2 = buffered.read();
        buffered.reset();
        if (b1 == 0x1f && b2 == 0x8b) {
            return new BufferedInputStream(new GZIPInputStream(buffered, 65536), 65536);
        }
        return buffered;
    }

    private static long retryAfter(HttpURLConnection connection) {
        String value = connection.getHeaderField("Retry-After");
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
