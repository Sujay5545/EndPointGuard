package com.endpointguard.review.provider;

import com.endpointguard.common.config.AppProperties;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OpenAiLlmReviewProvider implements LlmReviewProvider {

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public ReviewDecision review(ReviewRequest request) {
        if (appProperties == null || appProperties.getLlm() == null || appProperties.getLlm().getOpenai() == null
                || appProperties.getLlm().getOpenai().getApiKey() == null
                || appProperties.getLlm().getOpenai().getApiKey().isBlank()) {
            return new ReviewDecision("LOW", 0.0, "OpenAI key not configured", List.of("No API key configured"), providerName(), true);
        }

        String prompt = "Return JSON only with fields: decision, score, summary, findings. " +
                "Decision should be LOW, MEDIUM, or HIGH. Score should be a number between 0 and 100. " +
            "This review is advisory and must not replace deterministic risk. " +
            "Repository: " + request.repository() + "\nSummary: " + request.changeSummary() +
            "\nAffected endpoints and deterministic risk context: " + request.affectedEndpoints() +
            "\nDeterministic risk score (0-100): " + request.computedRiskScore() +
            "\nDiff: " + request.diff();

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", "gpt-4o-mini",
                    "temperature", 0,
                    "messages", List.of(Map.of("role", "user", "content", prompt))));
            var response = restTemplate.postForEntity("https://api.openai.com/v1/chat/completions", new org.springframework.http.HttpEntity<>(body, buildHeaders()), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("OpenAI provider returned no valid response");
            }

            JsonNode payload = objectMapper.readTree(response.getBody());
            String content = payload.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("OpenAI response missing content");
            }

            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
            }
            JsonNode parsed = objectMapper.readTree(json);
            String decision = parsed.path("decision").asText("LOW");
            double score = parsed.path("score").asDouble(0.0);
            String summary = parsed.path("summary").asText("Provider response reviewed");
            List<String> findings = new ArrayList<>();
            for (JsonNode node : parsed.path("findings")) {
                findings.add(node.asText());
            }
            return new ReviewDecision(decision, score, summary, findings, providerName(), false);
        } catch (Exception exception) {
            return new ReviewDecision("LOW", 0.0, "OpenAI provider failed; using deterministic fallback", List.of("Provider request or response was invalid"), providerName(), true);
        }
    }

    private org.springframework.http.HttpHeaders buildHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(appProperties.getLlm().getOpenai().getApiKey());
        return headers;
    }
}
