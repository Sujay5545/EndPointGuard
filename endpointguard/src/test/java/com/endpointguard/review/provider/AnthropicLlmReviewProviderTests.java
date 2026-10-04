 package com.endpointguard.review.provider;

import com.endpointguard.common.config.AppProperties;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnthropicLlmReviewProviderTests {

    @Test
    void parsesNormalJsonResponse() {
        AppProperties properties = new AppProperties();
        properties.getLlm().getAnthropic().setApiKey("test-anthropic-key");

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(anthropicResponse(
                        "msg_123",
                        "{\"decision\":\"MEDIUM\",\"score\":72.5,\"summary\":\"Review complete\",\"findings\":[\"Check the payment validation\"]}",
                        "claude-3-5-haiku-20241022")));

        AnthropicLlmReviewProvider provider = new AnthropicLlmReviewProvider(restTemplate, properties, new ObjectMapper());

        ReviewDecision decision = provider.review(new ReviewRequest(
                "acme/payments-api",
                "Protect the payment flow",
                "diff --git a/src/main/java/com/acme/PaymentValidator.java b/src/main/java/com/acme/PaymentValidator.java",
                "Affected endpoints: /api/payments, deterministic risk context: MEDIUM",
                72.5
        ));

        assertThat(decision.decision()).isEqualTo("MEDIUM");
        assertThat(decision.score()).isEqualTo(72.5);
        assertThat(decision.summary()).isEqualTo("Review complete");
        assertThat(decision.findings()).containsExactly("Check the payment validation");
        assertThat(decision.fallback()).isFalse();
    }

    @Test
    void parsesFencedJsonResponse() {
        AppProperties properties = new AppProperties();
        properties.getLlm().getAnthropic().setApiKey("test-anthropic-key");

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(anthropicResponse(
                        "msg_456",
                        "```json\n{\"decision\":\"HIGH\",\"score\":92.0,\"summary\":\"Looks safe\",\"findings\":[\"No critical issue found\"]}\n```",
                        "claude-3-5-sonnet-20241022")));

        AnthropicLlmReviewProvider provider = new AnthropicLlmReviewProvider(restTemplate, properties, new ObjectMapper());

        ReviewDecision decision = provider.review(new ReviewRequest(
                "acme/payments-api",
                "Add safer validation",
                "diff --git a/src/main/java/com/acme/PaymentValidator.java b/src/main/java/com/acme/PaymentValidator.java",
                "Affected endpoints: /api/payments, deterministic risk context: HIGH",
                81.0
        ));

        assertThat(decision.decision()).isEqualTo("HIGH");
        assertThat(decision.score()).isEqualTo(92.0);
        assertThat(decision.summary()).isEqualTo("Looks safe");
        assertThat(decision.findings()).containsExactly("No critical issue found");
        assertThat(decision.fallback()).isFalse();
    }

    @Test
    void fallsBackWhenResponseIsInvalid() {
        AppProperties properties = new AppProperties();
        properties.getLlm().getAnthropic().setApiKey("test-anthropic-key");

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(anthropicResponse(
                        "msg_789",
                        "This is not valid JSON.",
                        "claude-3-5-haiku-20241022")));

        AnthropicLlmReviewProvider provider = new AnthropicLlmReviewProvider(restTemplate, properties, new ObjectMapper());

        ReviewDecision decision = provider.review(new ReviewRequest(
                "acme/payments-api",
                "Add safer validation",
                "diff --git a/src/main/java/com/acme/PaymentValidator.java b/src/main/java/com/acme/PaymentValidator.java",
                "Affected endpoints: /api/payments, deterministic risk context: HIGH",
                81.0
        ));

        assertThat(decision.fallback()).isTrue();
        assertThat(decision.summary()).contains("Anthropic provider failed");
        assertThat(decision.provider()).isEqualTo("anthropic");
    }

    @Test
    void logsSafeHttpFailureAndPreservesFallback() {
        AppProperties properties = new AppProperties();
        properties.getLlm().getAnthropic().setApiKey("test-anthropic-key");
        RestTemplate restTemplate = mock(RestTemplate.class);
        String providerError = "temporarily overloaded; key test-anthropic-key; "
                + "Authorization: Bearer secret-token; repo acme/payments-api diff PaymentValidator.java";
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Service Unavailable",
                        HttpHeaders.EMPTY,
                        ("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\""
                                + providerError + "\"}}")
                                .getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));
        Logger logger = (Logger) LoggerFactory.getLogger(AnthropicLlmReviewProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        ReviewDecision decision;
        try {
            decision = new AnthropicLlmReviewProvider(restTemplate, properties, new ObjectMapper()).review(
                    new ReviewRequest(
                            "acme/payments-api",
                            "Protect the payment flow",
                            "diff --git a/src/main/java/PaymentValidator.java",
                            "Affected endpoint /api/payments",
                            72.5));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(decision.fallback()).isTrue();
        assertThat(decision.provider()).isEqualTo("anthropic");
        assertThat(decision.summary()).isEqualTo("Anthropic provider failed; using deterministic fallback");
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("provider=anthropic", "httpStatus=503", "exceptionType=ServiceUnavailable",
                        "temporarily overloaded")
                .doesNotContain("test-anthropic-key", "secret-token", "acme/payments-api",
                        "PaymentValidator.java", "Protect the payment flow", "/api/payments");
    }

      private static String anthropicResponse(String id, String text, String model) {
        try {
          return new ObjectMapper().writeValueAsString(Map.of(
              "id", id,
              "type", "message",
              "role", "assistant",
              "content", List.of(Map.of("type", "text", "text", text)),
              "model", model));
        } catch (JsonProcessingException exception) {
          throw new AssertionError("Unable to create Anthropic test response", exception);
        }
      }
}
