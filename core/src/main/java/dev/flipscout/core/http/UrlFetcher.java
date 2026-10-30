package dev.flipscout.core.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/** {@link HttpFetcher} on top of HttpURLConnection (Java 8 has no java.net.http). */
public final class UrlFetcher implements HttpFetcher {
    /** Small enough that an aborted download stops after the first few KB. */
    private static final int BUFFER = 16 * 1024;

    private final String userAgent;
    private final SSLSocketFactory socketFactory;

    public UrlFetcher(String userAgent, SSLSocketFactory socketFactory) {
        this.userAgent = userAgent;
        this.socketFactory = socketFactory;
    }

    @Override
    public Response get(String url) throws IOException {
        return response(open(url));
    }

    @Override
    public Response post(String url, String contentType, byte[] body) throws IOException {
        HttpURLConnection connection = open(url);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        return response(connection);
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        if (socketFactory != null && connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(socketFactory);
        }
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        return connection;
    }

    private static Response response(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (in == null) {
            in = new ByteArrayInputStream(new byte[0]);
        }
        if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
            in = new GZIPInputStream(in, BUFFER);
        }
        return new Response(status, maybeGunzip(in), connection.getDate(), retryAfter(connection),
            connection::disconnect);
    }

    /** Some sources serve .json.gz files as plain binary: unwrap those too. */
    static InputStream maybeGunzip(InputStream in) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in, BUFFER);
        buffered.mark(2);
        int b1 = buffered.read();
        int b2 = buffered.read();
        buffered.reset();
        if (b1 == 0x1f && b2 == 0x8b) {
            return new BufferedInputStream(new GZIPInputStream(buffered, BUFFER), BUFFER);
        }
        return buffered;
    }

    private static long retryAfter(HttpURLConnection connection) {
        String value = connection.getHeaderField("Retry-After");
        if (value == null) {
            return 0;
        }
        try {
            // Seconds; Discord sends fractions ("0.75").
            return (long) Math.ceil(Double.parseDouble(value.trim()) * 1000.0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
