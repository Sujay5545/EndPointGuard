package com.endpointguard.review.provider;

import com.endpointguard.common.config.AppProperties;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnthropicLlmReviewProvider implements LlmReviewProvider {

    private static final Pattern CREDENTIAL_HEADER = Pattern.compile(
            "(?i)(authorization|x-api-key)\\s*[:=]\\s*[^\\s,;]+(?:\\s+[^\\s,;]+)?");

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String providerName() {
        return "anthropic";
    }

    @Override
    public ReviewDecision review(ReviewRequest request) {
        if (appProperties == null || appProperties.getLlm() == null || appProperties.getLlm().getAnthropic() == null
                || appProperties.getLlm().getAnthropic().getApiKey() == null
                || appProperties.getLlm().getAnthropic().getApiKey().isBlank()) {
            return new ReviewDecision("LOW", 0.0, "Anthropic key not configured", List.of("No API key configured"), providerName(), true);
        }

        String prompt = null;
        try {
            prompt = "Return JSON only with decision, score, summary, findings. This review is advisory "
                    + "and must not replace deterministic risk. Repository: " + request.repository()
                    + ". Summary: " + request.changeSummary() + ". Affected endpoints and deterministic risk context: "
                    + request.affectedEndpoints() + ". Deterministic risk score (0-100): "
                    + request.computedRiskScore() + ". Diff: " + request.diff();

            String body = objectMapper.writeValueAsString(Map.of(
                    "model", "claude-3-5-haiku-20241022",
                    "max_tokens", 1024,
                    "messages", List.of(Map.of("role", "user", "content", prompt))));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("x-api-key", appProperties.getLlm().getAnthropic().getApiKey());
            headers.set("anthropic-version", "2023-06-01");

            var response = restTemplate.postForEntity(
                    "https://api.anthropic.com/v1/messages",
                    new HttpEntity<>(body, headers),
                    String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Anthropic provider returned no valid response");
            }

            JsonNode payload = objectMapper.readTree(response.getBody());
            JsonNode parsed = extractReviewJson(payload);
            String decision = parsed.path("decision").asText("LOW");
            double score = parsed.path("score").asDouble(0.0);
            List<String> findings = new ArrayList<>();
            for (JsonNode node : parsed.path("findings")) {
                findings.add(node.asText());
            }
            return new ReviewDecision(
                    decision,
                    score,
                    parsed.path("summary").asText("Provider response reviewed"),
                    findings,
                    providerName(),
                    false
            );
        } catch (Exception exception) {
            String httpStatus = exception instanceof RestClientResponseException responseException
                ? Integer.toString(responseException.getStatusCode().value())
                : "unavailable";
            log.warn("Provider request failed: provider={} httpStatus={} exceptionType={} message={}",
                providerName(), httpStatus, exception.getClass().getSimpleName(),
                safeExceptionMessage(exception, appProperties.getLlm().getAnthropic().getApiKey(), request, prompt));
            return new ReviewDecision(
                    "LOW",
                    0.0,
                    "Anthropic provider failed; using deterministic fallback",
                    List.of("Provider request or response was invalid"),
                    providerName(),
                    true
            );
        }
    }

    private String safeExceptionMessage(Exception exception, String apiKey, ReviewRequest request, String prompt) {
        String message = exception.getMessage();
        if (exception instanceof RestClientResponseException responseException) {
            try {
                message = objectMapper.readTree(responseException.getResponseBodyAsString())
                        .path("error").path("message").asText(responseException.getStatusText());
            } catch (Exception ignored) {
                message = responseException.getStatusText();
            }
        }
        if (message == null || message.isBlank()) {
            message = "No exception message provided";
        }

        message = redact(message, apiKey);
        message = redact(message, prompt);
        message = redact(message, request.repository());
        message = redact(message, request.changeSummary());
        message = redact(message, request.diff());
        message = redact(message, request.affectedEndpoints());
        Matcher credentialMatcher = CREDENTIAL_HEADER.matcher(message);
        message = credentialMatcher.replaceAll("$1=[redacted]");
        message = redactTokens(message, request.changeSummary());
        message = redactTokens(message, request.diff());
        message = redactTokens(message, request.affectedEndpoints());
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static String redact(String message, String sensitiveValue) {
        if (sensitiveValue == null || sensitiveValue.isBlank()) {
            return message;
        }
        return message.replace(sensitiveValue, "[redacted]");
    }

    private static String redactTokens(String message, String sensitiveValue) {
        if (sensitiveValue == null || sensitiveValue.isBlank()) {
            return message;
        }
        Matcher matcher = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*").matcher(sensitiveValue);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 4) {
                message = message.replaceAll("(?i)(?<![A-Za-z0-9])" + Pattern.quote(token)
                    + "(?![A-Za-z0-9])", "[redacted]");
            }
        }
        return message;
    }

    private JsonNode extractReviewJson(JsonNode payload) {
        List<String> candidates = new ArrayList<>();
        collectTextCandidates(payload, candidates);

        for (String candidate : candidates) {
            String normalized = normalizeJsonCandidate(candidate);
            if (normalized == null || normalized.isBlank()) {
                continue;
            }
            try {
                JsonNode parsed = objectMapper.readTree(normalized);
                if (parsed.isObject() && (parsed.has("decision") || parsed.has("summary") || parsed.has("findings"))) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // continue to the next candidate; invalid provider payloads still fall back below
            }
        }

        throw new IllegalStateException("Anthropic response was not valid JSON review output");
    }

    private void collectTextCandidates(JsonNode node, List<String> candidates) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            candidates.add(node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                collectTextCandidates(element, candidates);
            }
            return;
        }
        if (node.isObject()) {
            if (node.has("text") && node.path("text").isTextual()) {
                candidates.add(node.path("text").asText());
            }
            if (node.has("content")) {
                collectTextCandidates(node.get("content"), candidates);
            }
        }
    }

    private String normalizeJsonCandidate(String candidate) {
        if (candidate == null) {
            return null;
        }
        String normalized = candidate.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```\\s*$", "");
        }

        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            normalized = normalized.substring(start, end + 1);
        }
        return normalized.trim();
    }
}
