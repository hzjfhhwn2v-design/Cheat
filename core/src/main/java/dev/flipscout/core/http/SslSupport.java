package dev.flipscout.core.http;

import dev.flipscout.core.Log;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * The vanilla launcher still runs 1.8.9 on Java 8u51, whose trust store predates Let's Encrypt
 * and Google Trust Services. This builds a socket factory that trusts the JVM's own roots plus a
 * few bundled public roots, used only for this mod's own connections.
 */
public final class SslSupport {
    private static final String[] BUNDLED = {
        "isrg_root_x1.pem",
        "isrg_root_x2.pem",
        "gts_root_r1.pem",
        "gts_root_r3.pem",
        "gts_root_r4.pem",
        "digicert_global_root_g2.pem",
        "usertrust_rsa_certification_authority.pem",
        "usertrust_ecc_certification_authority.pem",
        "ssl_com_root_certification_authority_rsa.pem",
        "ssl_com_root_certification_authority_ecc.pem",
        "amazon_root_ca_1.pem",
    };

    private SslSupport() {
    }

    /** Returns the combined socket factory, or null (meaning: use the JVM default) on failure. */
    public static SSLSocketFactory createSocketFactory() {
        try {
            KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
            extra.load(null, null);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            int loaded = 0;
            for (String name : BUNDLED) {
                InputStream in = SslSupport.class.getResourceAsStream("/flipscout/certs/" + name);
                if (in == null) {
                    continue;
                }
                try {
                    for (Certificate certificate : factory.generateCertificates(in)) {
                        extra.setCertificateEntry(name + "#" + loaded++, certificate);
                    }
                } finally {
                    in.close();
                }
            }
            final X509TrustManager defaults = trustManager(null);
            final X509TrustManager bundled = trustManager(extra);
            X509TrustManager combined = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    defaults.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        defaults.checkServerTrusted(chain, authType);
                    } catch (CertificateException e) {
                        bundled.checkServerTrusted(chain, authType);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    List<X509Certificate> all = new ArrayList<X509Certificate>(Arrays.asList(defaults.getAcceptedIssuers()));
                    all.addAll(Arrays.asList(bundled.getAcceptedIssuers()));
                    return all.toArray(new X509Certificate[0]);
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {combined}, null);
            return context.getSocketFactory();
        } catch (Exception e) {
            Log.warn("Could not prepare the extra certificates, using the Java ones", e);
            return null;
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("No X509TrustManager available");
    }
}
