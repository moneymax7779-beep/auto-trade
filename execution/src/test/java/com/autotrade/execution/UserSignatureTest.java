package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Review 19 Nov 2026: the proxy's signature over the user it names to the execution workers. */
class UserSignatureTest {

    private static final String SECRET = "test-secret-not-real";
    private static final byte[] BODY = "{\"enabled\":false}".getBytes(StandardCharsets.UTF_8);

    @Test
    void aSignedRequestPassesAndAnyChangeToItIsRefused() {
        String sig = UserSignature.sign(SECRET, "satyacp99", 1_000, "POST", "/api/execution/accounts/1/settings?x=1", BODY);
        assertThat(UserSignature.refusal(SECRET, "satyacp99", "1000", sig, "POST", "/api/execution/accounts/1/settings?x=1",
                BODY, 1_030)).isNull();
        assertThat(UserSignature.refusal(SECRET, "local-owner", "1000", sig, "POST", "/api/execution/accounts/1/settings?x=1",
                BODY, 1_030)).as("another user").contains("does not match");
        assertThat(UserSignature.refusal(SECRET, "satyacp99", "1000", sig, "POST", "/api/execution/accounts/2/settings?x=1",
                BODY, 1_030)).as("another account").contains("does not match");
        assertThat(UserSignature.refusal(SECRET, "satyacp99", "1000", sig, "POST", "/api/execution/accounts/1/settings?x=1",
                "{\"enabled\":true}".getBytes(StandardCharsets.UTF_8), 1_030)).as("another body").contains("does not match");
        assertThat(UserSignature.refusal(SECRET, "satyacp99", "1000", sig, "GET", "/api/execution/accounts/1/settings?x=1",
                BODY, 1_030)).as("another method").contains("does not match");
        assertThat(UserSignature.refusal("other-secret", "satyacp99", "1000", sig, "POST",
                "/api/execution/accounts/1/settings?x=1", BODY, 1_030)).as("another secret").contains("does not match");
    }

    @Test
    void anOldOrMissingSignatureIsRefused() {
        String sig = UserSignature.sign(SECRET, "satyacp99", 1_000, "GET", "/api/execution/accounts", null);
        assertThat(UserSignature.refusal(SECRET, "satyacp99", "1000", sig, "GET", "/api/execution/accounts", new byte[0], 1_061))
                .contains("too old");
        assertThat(UserSignature.refusal(SECRET, "satyacp99", "1000", sig, "GET", "/api/execution/accounts", new byte[0], 939))
                .contains("from the future");
        assertThat(UserSignature.refusal(SECRET, "satyacp99", null, null, "GET", "/api/execution/accounts", new byte[0], 1_000))
                .contains("not signed");
        assertThat(UserSignature.refusal(SECRET, "satyacp99", "soon", sig, "GET", "/api/execution/accounts", new byte[0], 1_000))
                .contains("not a number");
    }
}
