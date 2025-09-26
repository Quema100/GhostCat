package me.duckmain.ghostcat.tls;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;


// Utility to create a self-signed certificate keystore for TLS (POC).
public class SSLUtil {
    private static final Path KEYSTORE_PATH = Path.of("ghostcat-keystore.jks");
    private static final char[] STORE_PASS = "changeit".toCharArray();
    private static final String ALIAS = "ghostcat";


    public static void ensureServerKeystore() throws Exception {
        if (Files.exists(KEYSTORE_PATH)) return;
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate cert = SelfSignedCertGenerator.generate(kp, "CN=GhostCat", 3650);
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), STORE_PASS, new Certificate[]{cert});
        try (OutputStream os = Files.newOutputStream(KEYSTORE_PATH)) { ks.store(os, STORE_PASS); }
    }


    public static SSLContext serverSSLContext() throws Exception {
        ensureServerKeystore();
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream is = Files.newInputStream(KEYSTORE_PATH)) { ks.load(is, STORE_PASS); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, STORE_PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    public static SSLSocketFactory trustAllFactory() throws Exception {
        TrustManager[] tms = new TrustManager[]{ new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] xcs, String string) {}
            public void checkServerTrusted(X509Certificate[] xcs, String string) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tms, new SecureRandom());
        return ctx.getSocketFactory();
    }

    // For POC convenience: trust all certificates (client-side). In production DO NOT use.
    // public static void ensureTrustAll() throws Exception {
    //     TrustManager[] tms = new TrustManager[]{ new X509TrustManager() {
    //         public void checkClientTrusted(X509Certificate[] xcs, String string) {}
    //         public void checkServerTrusted(X509Certificate[] xcs, String string) {}
    //         public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    //     }};
    //     SSLContext ctx = SSLContext.getInstance("TLS");
    //     ctx.init(null, tms, new SecureRandom());
    //     SSLContext.setDefault(ctx);
    // }
}