package com.endpointguard.review.provider;

import com.endpointguard.common.config.AppProperties;
import com.endpointguard.metrics.config.MetricsConfig;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.endpointguard.review.service.LlmReviewService;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiLlmReviewProviderTests {

    private static final String MODEL = "gemini-3.8-flash";
    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/"
            + MODEL + ":generateContent";

        @Test
        void llmTimeoutIsIndependentFromTheSharedHttpTimeout() {
                AppProperties properties = new AppProperties();
                MetricsConfig config = new MetricsConfig();

                RestTemplate sharedRestTemplate = config.restTemplate(properties);
                RestTemplate llmRestTemplate = config.llmRestTemplate(properties);

                assertThat(configuredTimeout(sharedRestTemplate, "connectTimeout")).isEqualTo(Duration.ofSeconds(5));
                assertThat(configuredTimeout(sharedRestTemplate, "readTimeout")).isEqualTo(Duration.ofSeconds(5));
                assertThat(configuredTimeout(llmRestTemplate, "connectTimeout")).isEqualTo(Duration.ofSeconds(30));
                assertThat(configuredTimeout(llmRestTemplate, "readTimeout")).isEqualTo(Duration.ofSeconds(30));
                assertThat(GeminiLlmReviewProvider.class.getConstructors()[0].getParameters()[0]
                                .getAnnotation(Qualifier.class).value()).isEqualTo("llmRestTemplate");

                properties.getLlm().setTimeoutSeconds(45);
                RestTemplate configuredLlmRestTemplate = config.llmRestTemplate(properties);
                assertThat(configuredTimeout(configuredLlmRestTemplate, "connectTimeout")).isEqualTo(Duration.ofSeconds(45));
                assertThat(configuredTimeout(configuredLlmRestTemplate, "readTimeout")).isEqualTo(Duration.ofSeconds(45));
                assertThat(configuredTimeout(config.restTemplate(properties), "readTimeout")).isEqualTo(Duration.ofSeconds(5));
        }

    @Test
    void sendsGeminiGenerateContentRequestAndParsesCandidateText() throws Exception {
        AppProperties properties = configuredProperties();
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(response("MEDIUM", 63.0, "Review complete")));
        ObjectMapper objectMapper = new ObjectMapper();
        GeminiLlmReviewProvider provider = new GeminiLlmReviewProvider(restTemplate, properties, objectMapper);
        ReviewRequest request = request();

        ReviewDecision decision = provider.review(request);

        assertThat(decision.decision()).isEqualTo("MEDIUM");
        assertThat(decision.score()).isEqualTo(63.0);
        assertThat(decision.summary()).isEqualTo("Review complete");
        assertThat(decision.findings()).containsExactly("Check authorization boundaries");
        assertThat(decision.provider()).isEqualTo("gemini");
        assertThat(decision.fallback()).isFalse();

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq(URL), requestCaptor.capture(), eq(String.class));
        HttpEntity<?> httpEntity = requestCaptor.getValue();
        assertThat(httpEntity.getHeaders().getFirst("x-goog-api-key")).isEqualTo("test-gemini-key");
        assertThat(httpEntity.getHeaders().getContentType().toString()).isEqualTo("application/json");
        JsonNode body = objectMapper.readTree((String) httpEntity.getBody());
        assertThat(body.path("contents").path(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("contents").path(0).path("parts").path(0).path("text").asText())
                .contains("acme/payments-api", "PR #42", "/api/payments", "Deterministic risk score", "payment.patch");
        assertThat(body.path("generationConfig").path("responseMimeType").asText())
                .isEqualTo("application/json");
    }

    @Test
    void geminiProviderIsSelectedByLlmReviewService() {
        AppProperties properties = configuredProperties();
        properties.getLlm().setProvider("gemini");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(response("HIGH", 88.0, "Potential production issue")));
        GeminiLlmReviewProvider provider = new GeminiLlmReviewProvider(restTemplate, properties, new ObjectMapper());
        LlmReviewService service = new LlmReviewService(properties, List.of(provider));

        ReviewDecision decision = service.review(request());

        assertThat(decision.provider()).isEqualTo("gemini");
        assertThat(decision.decision()).isEqualTo("HIGH");
        assertThat(decision.score()).isEqualTo(88.0);
        assertThat(decision.fallback()).isFalse();
    }

    @Test
    void invalidCandidateResponseUsesProviderFallback() {
        AppProperties properties = configuredProperties();
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                {"candidates":[{"content":{"parts":[{"text":"not JSON"}]}}]}
                """));
        GeminiLlmReviewProvider provider = new GeminiLlmReviewProvider(restTemplate, properties, new ObjectMapper());

        ReviewDecision decision = provider.review(request());

        assertThat(decision.fallback()).isTrue();
        assertThat(decision.provider()).isEqualTo("gemini");
        assertThat(decision.summary()).contains("Gemini provider failed");
    }

    @Test
    void missingKeyAndTimeoutFallbackWithoutLoggingOrExposingTheKey() {
        AppProperties missingKeyProperties = new AppProperties();
        RestTemplate restTemplate = mock(RestTemplate.class);
        GeminiLlmReviewProvider missingKeyProvider = new GeminiLlmReviewProvider(
                restTemplate, missingKeyProperties, new ObjectMapper());

        ReviewDecision missingKeyDecision = missingKeyProvider.review(request());

        assertThat(missingKeyDecision.fallback()).isTrue();
        assertThat(missingKeyDecision.summary()).contains("Gemini API key not configured");
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));

        AppProperties properties = configuredProperties();
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));
        ReviewDecision timeoutDecision = new GeminiLlmReviewProvider(restTemplate, properties, new ObjectMapper())
                .review(request());

        assertThat(timeoutDecision.fallback()).isTrue();
        assertThat(timeoutDecision.summary()).contains("Gemini provider failed");
        assertThat(timeoutDecision.toString()).doesNotContain("test-gemini-key");
    }

        @Test
        void providerFailureLogsSafeHttpDiagnosticsAndReturnsFallback() {
                AppProperties properties = configuredProperties();
                RestTemplate restTemplate = mock(RestTemplate.class);
                String providerError = "request rejected for test-gemini-key; Authorization: Bearer secret-token; "
                                + "repository acme/payments-api diff payment.patch";
                when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                                .thenThrow(HttpClientErrorException.create(
                                                HttpStatus.UNAUTHORIZED,
                                                "Unauthorized",
                                                HttpHeaders.EMPTY,
                                                ("{\"error\":{\"message\":\"" + providerError + "\"}}")
                                                                .getBytes(StandardCharsets.UTF_8),
                                                StandardCharsets.UTF_8));
                Logger logger = (Logger) LoggerFactory.getLogger(GeminiLlmReviewProvider.class);
                ListAppender<ILoggingEvent> appender = new ListAppender<>();
                appender.start();
                logger.addAppender(appender);

                ReviewDecision decision;
                try {
                        decision = new GeminiLlmReviewProvider(restTemplate, properties, new ObjectMapper()).review(request());
                } finally {
                        logger.detachAppender(appender);
                        appender.stop();
                }

                assertThat(decision.fallback()).isTrue();
                assertThat(decision.provider()).isEqualTo("gemini");
                assertThat(decision.summary()).isEqualTo("Gemini provider failed; using deterministic fallback");
                assertThat(appender.list).hasSize(1);
                ILoggingEvent event = appender.list.get(0);
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("provider=gemini", "httpStatus=401", "exceptionType=Unauthorized")
                                .doesNotContain("test-gemini-key", "secret-token", "acme/payments-api", "payment.patch");
        }

    private static AppProperties configuredProperties() {
        AppProperties properties = new AppProperties();
        properties.getLlm().setProvider("gemini");
        properties.getLlm().getGemini().setApiKey("test-gemini-key");
        return properties;
    }

        private static Duration configuredTimeout(RestTemplate restTemplate, String fieldName) {
                SimpleClientHttpRequestFactory requestFactory =
                                (SimpleClientHttpRequestFactory) restTemplate.getRequestFactory();
                Number timeoutMillis = (Number) ReflectionTestUtils.getField(requestFactory, fieldName);
                return Duration.ofMillis(timeoutMillis.longValue());
        }

    private static ReviewRequest request() {
        return new ReviewRequest(
                "acme/payments-api",
                "PR #42: validate payment authorization",
                "FILE src/PaymentService.java\n+ payment.patch",
                "POST /api/payments (HIGH)\nDeterministic risk tier: MEDIUM; score: 0.63",
                63.0);
    }

    private static String response(String decision, double score, String summary) {
        return """
                {
                  "candidates": [{
                    "content": {
                      "role": "model",
                      "parts": [{"text": "{\\"decision\\":\\"%s\\",\\"score\\":%s,\\"summary\\":\\"%s\\",\\"findings\\":[\\"Check authorization boundaries\\"]}"}]
                    },
                    "finishReason": "STOP"
                  }]
                }
                """.formatted(decision, score, summary);
    }
}
