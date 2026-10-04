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
public class OllamaLlmReviewProvider implements LlmReviewProvider {

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String providerName() {
        return "ollama";
    }

    @Override
    public ReviewDecision review(ReviewRequest request) {
        if (appProperties == null || appProperties.getLlm() == null || appProperties.getLlm().getOllama() == null
                || appProperties.getLlm().getOllama().getBaseUrl() == null
                || appProperties.getLlm().getOllama().getBaseUrl().isBlank()) {
            return new ReviewDecision("LOW", 0.0, "Ollama not configured", List.of("No Ollama base URL configured"), providerName(), true);
        }

        try {
                String prompt = "Return JSON with decision, score, summary, findings. This review is advisory "
                    + "and must not replace deterministic risk. Repository: " + request.repository()
                    + ". Summary: " + request.changeSummary() + ". Affected endpoints and deterministic risk context: "
                    + request.affectedEndpoints() + ". Deterministic risk score (0-100): "
                    + request.computedRiskScore() + ". Diff: " + request.diff();
                String body = objectMapper.writeValueAsString(Map.of(
                    "model", "llama3", "stream", false, "format", "json", "prompt", prompt));
            var response = restTemplate.postForEntity(appProperties.getLlm().getOllama().getBaseUrl() + "/api/generate", body, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Ollama provider returned no valid response");
            }

            JsonNode payload = objectMapper.readTree(response.getBody());
            String content = payload.path("response").asText(null);
            if (content == null) {
                throw new IllegalStateException("Ollama response missing content");
            }

            JsonNode parsed = objectMapper.readTree(content);
            String decision = parsed.path("decision").asText("LOW");
            double score = parsed.path("score").asDouble(0.0);
            List<String> findings = new ArrayList<>();
            for (JsonNode node : parsed.path("findings")) {
                findings.add(node.asText());
            }
            return new ReviewDecision(decision, score, parsed.path("summary").asText("Provider response reviewed"), findings, providerName(), false);
        } catch (Exception exception) {
            return new ReviewDecision("LOW", 0.0, "Ollama provider failed; using deterministic fallback", List.of("Provider request or response was invalid"), providerName(), true);
        }
    }
}
