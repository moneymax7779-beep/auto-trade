package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class WebPushCryptoTest {

    /** RFC 8291 Appendix A: the worked example, byte for byte. */
    @Test
    void encryptsTheRfc8291ExampleExactly() throws Exception {
        byte[] asPrivate = WebPushCrypto.unb64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw");
        byte[] asPublic = WebPushCrypto.unb64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        byte[] uaPublic = WebPushCrypto.unb64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] auth = WebPushCrypto.unb64("BTBZMqHH6r4Tts7J_aSIgg");
        byte[] salt = WebPushCrypto.unb64("DGv6ra1nlYgDCS1FRnbzlw");
        KeyPair as = new KeyPair(WebPushCrypto.publicKey(asPublic), WebPushCrypto.privateKey(asPrivate));
        byte[] body = WebPushCrypto.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8),
                uaPublic, auth, as, salt);
        assertThat(WebPushCrypto.b64(body)).isEqualTo("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6"
                + "e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    /** A browser's side: the receiver decrypts what was sent with a fresh key and salt. */
    @Test
    void theReceiverDecryptsAFreshMessage() throws Exception {
        KeyPair ua = WebPushCrypto.generate();
        byte[] uaPublic = WebPushCrypto.point((java.security.interfaces.ECPublicKey) ua.getPublic());
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        byte[] message = "{\"title\":\"auto-trade · LIVE\",\"body\":\"obc bought NIFTY 24650 CE\"}".getBytes(StandardCharsets.UTF_8);
        byte[] body = WebPushCrypto.encrypt(message, uaPublic, auth);
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int idlen = body[20];
        byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idlen);
        KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
        ecdh.init(ua.getPrivate());
        ecdh.doPhase(WebPushCrypto.publicKey(asPublic), true);
        byte[] ikm = WebPushCrypto.hkdf(auth, ecdh.generateSecret(),
                WebPushCrypto.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic), 32);
        byte[] cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = gcm.doFinal(Arrays.copyOfRange(body, 21 + idlen, body.length));
        assertThat(plain[plain.length - 1]).as("last-record delimiter").isEqualTo((byte) 2);
        assertThat(Arrays.copyOf(plain, plain.length - 1)).isEqualTo(message);
    }

    @Test
    void vapidSignsAnEs256TokenForThePushServicesOrigin() throws Exception {
        String[] keys = WebPushCrypto.newKeys();
        String header = WebPushCrypto.vapid("https://fcm.googleapis.com/fcm/send/abc:def", "mailto:ops@example.invalid",
                keys[0], keys[1], 1_800_000_000L);
        assertThat(header).startsWith("vapid t=").endsWith(", k=" + keys[0]);
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        String claims = new String(WebPushCrypto.unb64(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"aud\":\"https://fcm.googleapis.com\"").contains("\"exp\":" + (1_800_000_000L + 3_600))
                .contains("\"sub\":\"mailto:ops@example.invalid\"");
        Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
        verify.initVerify(WebPushCrypto.publicKey(WebPushCrypto.unb64(keys[0])));
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(WebPushCrypto.unb64(parts[2]))).isTrue();
        assertThat(WebPushCrypto.unb64(parts[2])).hasSize(64);
    }
}
