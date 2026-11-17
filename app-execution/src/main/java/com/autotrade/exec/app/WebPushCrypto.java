package com.autotrade.exec.app;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web push without a library (mobile phase 4, 17 Nov 2026), on the JDK's own P-256, HMAC-SHA256 and AES-GCM:
 * <ul>
 *   <li>{@link #encrypt}: RFC 8291 message encryption with the RFC 8188 "aes128gcm" content coding (one record);</li>
 *   <li>{@link #vapid}: the RFC 8292 Authorization header, an ES256 JWT for the push service's origin.</li>
 * </ul>
 * Keys travel as unpadded base64url: a public key as its 65-byte uncompressed point, the VAPID private key as its
 * 32-byte scalar.
 */
final class WebPushCrypto {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder D64 = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = p256();
    /** RFC 8188 record size: one record holds any message this app sends (well under 4 KB). */
    static final int RECORD_SIZE = 4096;

    private WebPushCrypto() {
    }

    static String b64(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    static byte[] unb64(String text) {
        return D64.decode(text.trim().replace('+', '-').replace('/', '_').replaceAll("=+$", ""));
    }

    /** A new P-256 key pair: {publicKey (65-byte point), privateKey (32-byte scalar)} as base64url. */
    static String[] newKeys() throws GeneralSecurityException {
        KeyPair pair = generate();
        return new String[] {b64(point((ECPublicKey) pair.getPublic())), b64(scalar((ECPrivateKey) pair.getPrivate()))};
    }

    /**
     * The aes128gcm body for one subscription: salt(16) | rs(4) | idlen(1)=65 | as_public(65) | AES-GCM(plaintext | 0x02).
     * {@code uaPublic} and {@code authSecret} are the browser's p256dh and auth.
     */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, generate(), salt);
    }

    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt)
            throws GeneralSecurityException {
        if (plaintext.length + 17 + 16 > RECORD_SIZE) {
            throw new IllegalArgumentException("push message too long: " + plaintext.length + " bytes");
        }
        byte[] asPublic = point((ECPublicKey) asKeys.getPublic());
        KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
        ecdh.init(asKeys.getPrivate());
        ecdh.doPhase(publicKey(uaPublic), true);
        byte[] shared = ecdh.generateSecret();
        // RFC 8291 3.4: IKM from the ECDH secret and the auth secret, keyed on both public keys
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = hkdf(authSecret, shared, keyInfo, 32);
        byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] sealed = gcm.doFinal(concat(plaintext, new byte[] {2}));     // 0x02: the last (only) record
        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
        return concat(header.array(), sealed);
    }

    /**
     * Authorization: {@code vapid t=<ES256 JWT>, k=<public key>} for a push endpoint's origin, valid 1 h (a new token is
     * signed for every message, and some push services refuse long-lived ones; review 17 Nov).
     */
    static String vapid(String endpoint, String subject, String publicKey, String privateKey, long nowEpochSeconds)
            throws GeneralSecurityException {
        java.net.URI uri = java.net.URI.create(endpoint);
        String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64(("{\"aud\":\"" + audience + "\",\"exp\":" + (nowEpochSeconds + 3600) + ",\"sub\":\""
                + subject.replace("\"", "") + "\"}").getBytes(StandardCharsets.UTF_8));
        Signature es256 = Signature.getInstance("SHA256withECDSAinP1363Format");   // r || s, as JWS wants
        es256.initSign(privateKey(unb64(privateKey)));
        es256.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return "vapid t=" + header + "." + claims + "." + b64(es256.sign()) + ", k=" + publicKey;
    }

    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] okm = mac.doFinal(concat(info, new byte[] {1}));              // one block is enough for 32 bytes
        return Arrays.copyOf(okm, length);
    }

    static KeyPair generate() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    static ECPublicKey publicKey(byte[] point) throws GeneralSecurityException {
        if (point.length != 65 || point[0] != 4) {
            throw new GeneralSecurityException("not an uncompressed P-256 point");
        }
        ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)), new BigInteger(1, Arrays.copyOfRange(point, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, P256));
    }

    static ECPrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), P256));
    }

    static byte[] point(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        fixed(key.getW().getAffineX(), out, 1);
        fixed(key.getW().getAffineY(), out, 33);
        return out;
    }

    static byte[] scalar(ECPrivateKey key) {
        byte[] out = new byte[32];
        fixed(key.getS(), out, 0);
        return out;
    }

    private static void fixed(BigInteger value, byte[] out, int at) {
        byte[] raw = value.toByteArray();                                    // may carry a sign byte or be shorter
        int n = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - n, out, at + 32 - n, n);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        ByteBuffer out = ByteBuffer.allocate(n);
        for (byte[] p : parts) {
            out.put(p);
        }
        return out.array();
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no P-256 in this JDK", e);
        }
    }
}
