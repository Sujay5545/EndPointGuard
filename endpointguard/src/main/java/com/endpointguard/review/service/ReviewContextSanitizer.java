package com.endpointguard.review.service;

import java.util.regex.Pattern;

public final class ReviewContextSanitizer {

    public static final int MAX_DESCRIPTION_CHARS = 4_000;

    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)((?:api[_-]?key|token|password|secret)\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',;]+"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b");

    private ReviewContextSanitizer() {
    }

    public static String redactAndTruncate(String value, int maxChars, String truncationMarker) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String redacted = ASSIGNED_SECRET.matcher(value).replaceAll("$1[REDACTED]");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("Bearer [REDACTED]");
        redacted = GITHUB_TOKEN.matcher(redacted).replaceAll("[REDACTED_GITHUB_TOKEN]");
        if (redacted.length() <= maxChars) {
            return redacted;
        }
        String marker = truncationMarker == null ? "[Context truncated]" : truncationMarker;
        if (marker.length() >= maxChars) {
            return marker.substring(0, maxChars);
        }
        return redacted.substring(0, maxChars - marker.length()) + marker;
    }
}