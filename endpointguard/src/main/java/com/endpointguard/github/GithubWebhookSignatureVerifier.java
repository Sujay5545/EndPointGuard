package com.endpointguard.github;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class GithubWebhookSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private GithubWebhookSignatureVerifier() {
    }

    public static String sign(String payload, String secret) {
        return PREFIX + toHex(hmacSha256(secret, payload));
    }

    public static void verify(String payload, String signatureHeader, String secret, String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            throw new IllegalArgumentException("X-GitHub-Delivery header is required");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new IllegalArgumentException("X-Hub-Signature-256 header is required; signature missing");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("GitHub webhook secret is not configured");
        }

        String expected = sign(payload, secret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signatureHeader.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Invalid X-Hub-Signature-256 signature value");
        }
    }

    private static byte[] hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKey);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to compute GitHub webhook signature", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
