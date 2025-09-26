package me.duckmain.ghostcat.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.KeyAgreement;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * X25519 기반 static keypair + ephemeral operations, HKDF-SHA256 key derivation
 */
public class CryptoUtils {
    private static KeyPair staticKP; // 서버/클라이언트 static keypair
    private static final SecureRandom RNG = new SecureRandom(); // 랜덤 생성기
    private static final ConcurrentHashMap<String, byte[]> peerStaticMap = new ConcurrentHashMap<>();

    // static keypair 생성
    public static void generateStaticKeypair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
        kpg.initialize(new NamedParameterSpec("X25519"));
        staticKP = kpg.generateKeyPair();
    }

    public static byte[] getStaticPublic() {
        return staticKP == null ? null : staticKP.getPublic().getEncoded();
    }

    public static void storePeerStatic(String nick, byte[] pub) {
        peerStaticMap.put(nick, Arrays.copyOf(pub, pub.length));
    }

    public static byte[] getPeerStatic(String nick) {
        return peerStaticMap.get(nick);
    }

    // ephemeral keypair 생성
    public static KeyPair generateEphemeral() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
        kpg.initialize(new NamedParameterSpec("X25519"));
        return kpg.generateKeyPair();
    }

    // ephemeral private key x peer static public -> shared secret
    public static byte[] sharedEphemeralStatic(PrivateKey ephPrivate, byte[] peerStaticEncoded) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("X25519");
        PublicKey peerPub = kf.generatePublic(new X509EncodedKeySpec(peerStaticEncoded));
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(ephPrivate);
        ka.doPhase(peerPub, true);
        return ka.generateSecret();
    }

    // static private x ephemeral public -> shared secret
    public static byte[] sharedStaticEphemeral(byte[] theirEphPublicEncoded) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("X25519");
        PublicKey theirEph = kf.generatePublic(new X509EncodedKeySpec(theirEphPublicEncoded));
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(staticKP.getPrivate());
        ka.doPhase(theirEph, true);
        return ka.generateSecret();
    }

    // HKDF extract+expand
    public static byte[] hkdf(byte[] ikm, byte[] salt, int length) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        byte[] realSalt = salt == null ? new byte[32] : salt;
        hmac.init(new SecretKeySpec(realSalt, "HmacSHA256"));

        byte[] prk = hmac.doFinal(ikm);
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int loc = 0;
        int counter = 1;

        while (loc < length) {
            hmac.reset();
            hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
            hmac.update(t);
            hmac.update((byte) counter); // counter 추가
            t = hmac.doFinal();
            int copy = Math.min(t.length, length - loc);
            System.arraycopy(t, 0, okm, loc, copy);
            loc += copy;
            counter++;
        }
        return okm;
    }

    public static byte[] randomIV() {
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        return iv;
    }

    public static byte[] encryptAESGCM(String plain, byte[] key, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
    }

    public static String decryptAESGCM(byte[] ct, byte[] key, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }
}
