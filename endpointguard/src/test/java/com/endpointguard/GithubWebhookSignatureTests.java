package com.endpointguard;

import com.endpointguard.github.GithubWebhookSignatureVerifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubWebhookSignatureTests {

    @Test
    void acceptsValidSignature() {
        String payload = "{\"action\":\"opened\"}";
        String secret = "topsecret";
        String signature = GithubWebhookSignatureVerifier.sign(payload, secret);

        assertThatCode(() -> GithubWebhookSignatureVerifier.verify(payload, signature, secret, "delivery-1"))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingSignature() {
        assertThatThrownBy(() -> GithubWebhookSignatureVerifier.verify("{\"action\":\"opened\"}", null, "topsecret", "delivery-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("signature");
    }

    @Test
    void rejectsInvalidSignature() {
        assertThatThrownBy(() -> GithubWebhookSignatureVerifier.verify("{\"action\":\"opened\"}", "sha256=deadbeef", "topsecret", "delivery-1"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("signature");
    }
}
