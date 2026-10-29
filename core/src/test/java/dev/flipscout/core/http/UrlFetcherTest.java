package dev.flipscout.core.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UrlFetcherTest {
    @Test
    void unwrapsGzipBodiesAndLeavesPlainOnesAlone() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        GZIPOutputStream out = new GZIPOutputStream(gz);
        out.write("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        out.close();
        assertEquals("{\"a\":1}", read(UrlFetcher.maybeGunzip(new ByteArrayInputStream(gz.toByteArray()))));
        assertEquals("{\"b\":2}", read(UrlFetcher.maybeGunzip(new ByteArrayInputStream("{\"b\":2}".getBytes(StandardCharsets.UTF_8)))));
        assertEquals("", read(UrlFetcher.maybeGunzip(new ByteArrayInputStream(new byte[0]))));
    }

    @Test
    void bundledCertificatesLoad() {
        assertNotNull(SslSupport.createSocketFactory());
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
