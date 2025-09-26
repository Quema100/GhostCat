package me.duckmain.ghostcat.tls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;


public class SelfSignedCertGenerator {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Self-signed X.509 인증서 생성
     *
     * @param keyPair 인증서용 KeyPair
     * @param dn     Distinguished Name, 예: "CN=localhost"
     * @param days   유효기간 (일 단위)
     * @return X509Certificate
     * @throws Exception
     */
    public static X509Certificate generate(KeyPair keyPair, String dn, int days) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 1000L * 60); // 1분 전
        Date notAfter = new Date(now + days * 86400000L);

        BigInteger serialNumber = BigInteger.valueOf(now); // 유니크한 시리얼

        X500Name issuer = new X500Name(dn);
        X500Name subject = new X500Name(dn);

        // 인증서 빌더
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer,
                serialNumber,
                notBefore,
                notAfter,
                subject,
                keyPair.getPublic()
        );

        // 서명자(ContentSigner) 생성
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(keyPair.getPrivate());

        // X509Certificate 생성
        X509CertificateHolder holder = certBuilder.build(signer);
        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(holder);
    }

    /**
     * 테스트용 메인
     */
    public static void main(String[] args) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X509Certificate cert = generate(kp, "CN=localhost", 365);
        System.out.println("Generated Certificate:");
        System.out.println(cert);
    }
}