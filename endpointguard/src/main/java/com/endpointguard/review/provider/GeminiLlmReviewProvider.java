package com.endpointguard.review.provider;

import com.endpointguard.common.config.AppProperties;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class GeminiLlmReviewProvider implements LlmReviewProvider {

    private static final String DEFAULT_MODEL = "gemini-3.8-flash";
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern CREDENTIAL_HEADER = Pattern.compile(
            "(?i)(authorization|x-goog-api-key)\\s*[:=]\\s*[^\\s,;]+(?:\\s+[^\\s,;]+)?");

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public GeminiLlmReviewProvider(
            @Qualifier("llmRestTemplate") RestTemplate restTemplate,
            AppProperties appProperties,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "gemini";
    }

    @Override
    public ReviewDecision review(ReviewRequest request) {
        AppProperties.Llm.Gemini config = appProperties == null || appProperties.getLlm() == null
                ? null
                : appProperties.getLlm().getGemini();
        if (config == null || config.getApiKey() == null || config.getApiKey().isBlank()) {
            return fallback("Gemini API key not configured");
        }

        String prompt = null;
        try {
            String model = config.getModel() == null || config.getModel().isBlank()
                    ? DEFAULT_MODEL
                    : config.getModel().trim();
            if (!MODEL_ID.matcher(model).matches()) {
                throw new IllegalArgumentException("Gemini model name contains unsupported characters");
            }

            prompt = "Review the pull request using the repository, PR summary, changed code, affected "
                    + "endpoints, and deterministic risk context below. Return only a JSON object with exactly "
                    + "these fields: decision (LOW, MEDIUM, or HIGH), score (number 0-100), summary (string), "
                    + "findings (array of strings). This review is advisory and must never replace or modify the "
                    + "deterministic risk score.\nRepository: " + request.repository()
                    + "\nPR summary: " + request.changeSummary()
                    + "\nChanged code diff:\n" + request.diff()
                    + "\nAffected endpoints and deterministic risk context:\n" + request.affectedEndpoints()
                    + "\nDeterministic risk score (0-100): " + request.computedRiskScore();

            String body = objectMapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of(
                            "role", "user",
                            "parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "maxOutputTokens", 1024)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("x-goog-api-key", config.getApiKey());
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
            var response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Gemini returned no valid response");
            }

            JsonNode responseJson = objectMapper.readTree(response.getBody());
            JsonNode reviewJson = extractReviewJson(responseJson);
            String decision = reviewJson.path("decision").asText(null);
            JsonNode scoreNode = reviewJson.get("score");
            String summary = reviewJson.path("summary").asText(null);
            JsonNode findingsNode = reviewJson.get("findings");
            if (decision == null || scoreNode == null || !scoreNode.isNumber()
                    || summary == null || !findingsNode.isArray()) {
                throw new IllegalStateException("Gemini response did not match the review JSON contract");
            }

            List<String> findings = new ArrayList<>();
            for (JsonNode finding : findingsNode) {
                if (!finding.isTextual()) {
                    throw new IllegalStateException("Gemini findings must be strings");
                }
                findings.add(finding.asText());
            }
            return new ReviewDecision(decision, scoreNode.asDouble(), summary, findings, providerName(), false);
        } catch (Exception exception) {
            String httpStatus = exception instanceof RestClientResponseException responseException
                    ? Integer.toString(responseException.getStatusCode().value())
                    : "unavailable";
            log.warn("Provider request failed: provider={} httpStatus={} exceptionType={} message={}",
                    providerName(), httpStatus, exception.getClass().getSimpleName(),
                    safeExceptionMessage(exception, config.getApiKey(), request, prompt));
            return fallback("Gemini provider failed; using deterministic fallback");
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
        Matcher matcher = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*").matcher(sensitiveValue);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 4) {
                message = message.replaceAll("(?i)(?<![A-Za-z0-9._/-])" + Pattern.quote(token)
                        + "(?![A-Za-z0-9._/-])", "[redacted]");
            }
        }
        return message;
    }

    private JsonNode extractReviewJson(JsonNode response) throws Exception {
        for (JsonNode candidate : response.path("candidates")) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (part.hasNonNull("text") && !part.path("thought").asBoolean(false)) {
                    text.append(part.path("text").asText());
                }
            }
            String normalized = normalizeJson(text.toString());
            if (normalized.isBlank()) continue;
            try {
                JsonNode parsed = objectMapper.readTree(normalized);
                if (parsed.isObject()) return parsed;
            } catch (Exception ignored) {
                // Try any additional text candidate; an invalid final response falls back below.
            }
        }
        throw new IllegalStateException("Gemini response contained no review JSON candidate");
    }

    private static String normalizeJson(String text) {
        String normalized = text == null ? "" : text.trim();
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

    private ReviewDecision fallback(String summary) {
        return new ReviewDecision("LOW", 0.0, summary,
                List.of("Provider request or response was invalid"), providerName(), true);
    }
}