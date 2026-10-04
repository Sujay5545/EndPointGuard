package com.endpointguard.review.provider;

import com.endpointguard.common.config.AppProperties;
import com.endpointguard.review.dto.GroqReviewOutput;
import com.endpointguard.review.dto.GroqStructuredReviewOutput;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GroqLlmReviewProviderTests {

    @Test
    void mapsStructuredReviewResponseToCompatibleDecisionAndRetainsAnalysis() {
        GroqReviewOutput output = analysis(
                GroqReviewOutput.RiskLevel.MEDIUM,
                64.0,
                "Review the compatibility impact.",
                List.of(),
                List.of(new GroqReviewOutput.FileAnalysis(
                        "src/PaymentController.java", "Tightens payment validation", "Adds a guard",
                        "Rejects invalid payment data", "Protects the payment workflow",
                        GroqReviewOutput.RiskLevel.MEDIUM,
                        List.of(GroqReviewOutput.RiskCategory.BUSINESS_LOGIC_CHANGE),
                        "Invalid input is rejected", List.of("The diff adds the validation guard."),
                        List.of(new GroqReviewOutput.EndpointImpact(
                                "GET", "/api/payments", "HIGH", "Payment response changed",
                                "The changed file maps to this endpoint.")), 0.9)));
        ProviderFixture fixture = fixture(output);

        ReviewDecision decision = fixture.provider().review(request());

        assertThat(decision.decision()).isEqualTo("MEDIUM");
        assertThat(decision.score()).isEqualTo(64.0);
        assertThat(decision.summary()).isEqualTo("Review the compatibility impact.");
        assertThat(decision.findings()).isEmpty();
        assertThat(decision.analysis()).isEqualTo(output);
        assertThat(decision.analysis().files().get(0).affectedEndpoints())
                .containsExactly(new GroqReviewOutput.EndpointImpact(
                        "GET", "/api/payments", "HIGH", "Payment response changed",
                        "The changed file maps to this endpoint."));
        assertThat(decision.analysis().endpointImpact()).containsExactlyElementsOf(
                decision.analysis().files().get(0).affectedEndpoints());
        assertThat(decision.provider()).isEqualTo("groq");
        assertThat(decision.fallback()).isFalse();
        assertThat(request().computedRiskScore()).isEqualTo(64.0);
        verify(fixture.requestSpec()).advisors(any(Consumer.class));
    }

    @Test
    void removedReturnStatementIsReportedAsPotentialBuildBreakingRisk() {
        String regressionDiff = """
                @@ -4,7 +4,7 @@ import { Link, useParams, useLoaderData } from 'react-router-dom';
                 export default function Github() {
                     const data = useLoaderData();
                -    return (
                +     (
                     <div className="min-h-screen">
                """;
        ReviewRequest regressionRequest = new ReviewRequest(
                "acme/frontend",
                "Update Github page layout",
                regressionDiff,
                "No configured endpoints were matched.",
                18.0,
                List.of(new ReviewRequest.ChangedFile("src/Components/Github.jsx", 1, 1, regressionDiff)),
                "The page layout change should preserve the component render.");
        GroqReviewOutput output = analysis(
                GroqReviewOutput.RiskLevel.CRITICAL,
                96.0,
                "Removing the return before JSX may break rendering or compilation.",
                List.of(new GroqReviewOutput.CriticalFinding(
                        GroqReviewOutput.RiskLevel.CRITICAL,
                        GroqReviewOutput.RiskCategory.BUILD_BREAKING,
                        "src/Components/Github.jsx",
                        "The component's return statement was removed before its JSX expression.",
                        "The component may no longer return its intended React element.",
                        "The diff removes `return (` and replaces it with `(`.",
                        "Restore the return statement or verify the intended expression is returned.")),
                List.of(new GroqReviewOutput.FileAnalysis(
                        "src/Components/Github.jsx", "Removes the component return keyword",
                        "`return (` becomes `(`", "The component loads data and returns JSX",
                        "The Github page may fail to render for users", GroqReviewOutput.RiskLevel.CRITICAL,
                        List.of(GroqReviewOutput.RiskCategory.BUILD_BREAKING,
                                GroqReviewOutput.RiskCategory.RUNTIME_BREAKING),
                        "A JSX expression is left without an explicit component return",
                        List.of("The diff removes `return (` before the JSX element."), List.of(), 0.98)));
        ProviderFixture fixture = fixture(output);

        ReviewDecision decision = fixture.provider().review(regressionRequest);

        assertThat(decision.fallback()).isFalse();
        assertThat(decision.decision()).isEqualTo("CRITICAL");
        assertThat(decision.analysis().files().get(0).riskCategories())
                .contains(GroqReviewOutput.RiskCategory.BUILD_BREAKING);
        assertThat(decision.analysis().criticalFindings()).hasSize(1);
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(fixture.requestSpec()).system(systemPrompt.capture());
        verify(fixture.requestSpec()).user(userPrompt.capture());
        assertThat(systemPrompt.getValue())
                .contains("production code review", "correctness before", "Removing a return",
                        "Diff size does not determine risk", "verified build failure",
                        "Always return a non-null riskAlignment object",
                        "Set alignment to ALIGNED", "Set it to DIFFERENT",
                        "advisory risk is higher, lower",
                        "Every file must have 1 to 3 riskCategories",
                        "BUILD_BREAKING", "DOCUMENTATION_ONLY", "FORMATTING_ONLY");
        assertThat(userPrompt.getValue())
                .contains("src/Components/Github.jsx", "return (", "Affected endpoints",
                        "Deterministic EndpointGuard risk score", "PR description/body:",
                        "preserve the component render", "Surrounding source context: unavailable",
                        "Build/test/lint evidence: unavailable",
                        "all risk-alignment fields on every response");
    }

    @Test
    void promptMarksPrDescriptionUnavailableAndStructuredResponseStillParses() {
        ProviderFixture fixture = fixture(analysis(
                GroqReviewOutput.RiskLevel.LOW, 12.0, "No material risk found.", List.of(),
                List.of(new GroqReviewOutput.FileAnalysis(
                        "README.md", "Documentation update", "Clarifies setup", "Documents project usage",
                        "No direct runtime impact", GroqReviewOutput.RiskLevel.LOW,
                        List.of(GroqReviewOutput.RiskCategory.DOCUMENTATION_ONLY), "Documentation only",
                        List.of("README line changed"), List.of(), 0.9))));

        ReviewDecision decision = fixture.provider().review(request());

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(fixture.requestSpec()).user(userPrompt.capture());
        assertThat(userPrompt.getValue()).contains("PR description/body: unavailable",
                "Surrounding source context: unavailable", "Build/test/lint evidence: unavailable");
        assertThat(decision.fallback()).isFalse();
        assertThat(decision.analysis().files()).hasSize(1);
        assertThat(decision.analysis().files().get(0).riskCategories())
                .contains(GroqReviewOutput.RiskCategory.DOCUMENTATION_ONLY);
    }

    @ParameterizedTest
    @MethodSource("structuredRiskCategoryCases")
        void preservesStructuredRiskCategoriesForHighValueReviewScenarios(String label, String diff, List<GroqReviewOutput.RiskCategory> expectedCategories) {
        GroqReviewOutput output = analysis(
                GroqReviewOutput.RiskLevel.MEDIUM,
                58.0,
                "Structured review keeps the reviewer evidence and categories for " + label + ".",
                List.of(),
                List.of(new GroqReviewOutput.FileAnalysis(
                        "src/Service.java",
                        "Review scenario: " + label,
                        "Changed code in a targeted way",
                        "Service implements application behavior",
                        "The change can affect service behavior or runtime safety",
                        GroqReviewOutput.RiskLevel.MEDIUM,
                        expectedCategories,
                        "Evidence shows the exact diff and impact.",
                        List.of("Observed change: " + diff),
                        List.of(),
                        0.86)));

        GroqReviewOutput mappedFixture = GroqLlmReviewProvider.mapStructuredOutput(
                toStructuredReviewOutput(output));
        assertThat(GroqLlmReviewProvider.isValid(mappedFixture))
                .withFailMessage("Invalid mapped test fixture predicates: %s",
                        GroqLlmReviewProvider.validationDiagnostics(mappedFixture).get("failedPredicates"))
                .isTrue();

        ReviewDecision decision = fixture(output).provider().review(request());

        assertThat(decision.fallback()).isFalse();
        assertThat(decision.analysis()).isNotNull();
        assertThat(decision.analysis().files()).hasSize(1);
        assertThat(decision.analysis().files().get(0).riskCategories())
                .containsAll(expectedCategories);
        assertThat(decision.analysis().files().get(0).evidence()).contains("Observed change: " + diff);
    }

    @Test
    void invalidStructuredResponseUsesFallback() {
        GroqReviewOutput invalid = new GroqReviewOutput(
                "Invalid result", new GroqReviewOutput.OverallRisk(
                        GroqReviewOutput.RiskLevel.CRITICAL, 140.0, 0.9, "Invalid score"),
                "Invalid result", List.of(), List.of(), List.of(),
                new GroqReviewOutput.BusinessImpact("Cannot determine", "Unknown", 0.5),
                "Inspect", 0.9);
        ReviewDecision decision = fixture(invalid).provider().review(request());

        assertThat(decision.provider()).isEqualTo("groq");
        assertThat(decision.fallback()).isTrue();
        assertThat(decision.summary()).isEqualTo("Groq response was invalid; using deterministic fallback");
    }

    @Test
    void validationDiagnosticsIdentifyMissingStructuredPredicatesWithoutModelText() throws Exception {
        String modelTextSentinel = "private-model-output-sentinel";
        GroqReviewOutput output = new ObjectMapper().readValue("""
                {
                  "decision": "CRITICAL",
                  "score": 97.0,
                  "summary": "%s",
                  "findings": ["%s"]
                }
                """.formatted(modelTextSentinel, modelTextSentinel), GroqReviewOutput.class);

        Map<String, Object> diagnostics = GroqLlmReviewProvider.validationDiagnostics(output);

        assertThat(GroqLlmReviewProvider.isValid(output)).isFalse();
        assertThat(diagnostics)
                .containsEntry("dtoClass", "GroqReviewOutput")
                .containsEntry("overallAssessmentPresent", false)
                .containsEntry("overallRiskPresent", false)
                .containsEntry("overallRiskLevelValid", false)
                .containsEntry("changeSummaryPresent", false)
                .containsEntry("filesCount", 0)
                .containsEntry("filesCountValid", false)
                .containsEntry("criticalFindingsCount", 0)
                .containsEntry("endpointImpactCount", 0)
                .containsEntry("recommendationPresent", false)
                .containsEntry("riskAlignmentPresent", false);
        assertThat(diagnostics.get("failedPredicates"))
                .asList()
                .contains("overallAssessmentValid", "overallRiskPresent", "changeSummaryValid",
                        "recommendationValid", "filesCountValid");
        assertThat(diagnostics.toString()).doesNotContain(modelTextSentinel);
    }

        @Test
        void invalidFlatRiskCategoryIsRejectedAfterMapping() {
                List<GroqReviewOutput.RiskCategory> mappedCategories =
                                GroqLlmReviewProvider.mapRiskCategories(List.of("NOT_A_DEFINED_CATEGORY"));
                GroqReviewOutput output = analysis(
                                GroqReviewOutput.RiskLevel.LOW, 12.0, "Low-risk documentation change.", List.of(),
                                List.of(new GroqReviewOutput.FileAnalysis(
                                                "README.md", "Documentation update", "Clarifies setup", "Documents usage",
                                                "No direct runtime impact", GroqReviewOutput.RiskLevel.LOW, mappedCategories,
                                                "Documentation only", List.of("README section updated."), List.of(), 0.9)));

                assertThat(mappedCategories).isEmpty();
                assertThat(GroqLlmReviewProvider.isValid(output)).isFalse();
        }

    @Test
    void schemaDiagnosticsDescribeSpringAiNativeJsonSchemaWithoutEmittingSchemaText() {
        var diagnostics = GroqLlmReviewProvider.structuredSchemaDiagnostics(new ObjectMapper());

        assertThat(diagnostics)
                .containsEntry("responseFormatType", "JSON_SCHEMA")
                .containsEntry("schemaTopLevelType", "object")
                .containsEntry("riskAlignmentRequired", true)
                .containsEntry("riskAlignmentNullable", false)
                .containsEntry("additionalPropertiesFalse", true);
        assertThat(diagnostics.get("strictSetting")).isEqualTo(true);
        assertThat(diagnostics.get("propertyNames").toString())
                .contains("overallAssessment", "overallRiskLevel", "overallRiskScore", "overallRiskReason",
                        "files", "criticalFindings", "endpointImpact", "riskAlignment",
                        "businessImpactSummary", "businessImpactAffectedCapability",
                        "riskAlignmentDeterministicRisk", "riskAlignmentExplanation");
        assertThat((Integer) diagnostics.get("propertyCount")).isEqualTo(18);
        assertThat((Integer) diagnostics.get("requiredPropertiesCount"))
                .isEqualTo(diagnostics.get("propertyCount"));
        assertThat((Integer) diagnostics.get("enumFieldsCount")).isEqualTo(4);
        assertThat((Integer) diagnostics.get("nestedObjectArrayFieldsCount")).isPositive();
        assertThat(diagnostics.get("riskCategoriesMinItems")).isEqualTo(1);
        assertThat(diagnostics.get("riskCategoriesMaxItems")).isEqualTo(3);
        assertThat(diagnostics.get("schemaFeaturesToReview").toString())
                .contains("$schema")
                .doesNotContain("$defs", "$ref");
        assertThat(diagnostics.toString()).doesNotContain("overallAssessment.*type", "jsonSchema");
    }

    @Test
    void missingKeyFallsBackWithoutCallingTheChatClient() {
        ChatClient chatClient = mock(ChatClient.class);
        AppProperties properties = new AppProperties();
        GroqLlmReviewProvider provider = new GroqLlmReviewProvider(chatClient, properties, new ObjectMapper());

        ReviewDecision decision = provider.review(request());

        assertThat(decision.fallback()).isTrue();
        assertThat(decision.provider()).isEqualTo("groq");
        assertThat(decision.summary()).isEqualTo("Groq API key not configured; using deterministic fallback");
        verifyNoInteractions(chatClient);
    }

    @Test
    void springAiClientConfigurationAllowsMissingKeyForFallbackStartup() {
        AppProperties properties = new AppProperties();
        GroqChatConfiguration configuration = new GroqChatConfiguration();

        ChatClient client = configuration.groqChatClient(properties);

        assertThat(client).isNotNull();
        assertThat(new NoopApiKey().getValue()).isEmpty();
        assertThat(configuration.groqRetryTemplate(properties)).isNotNull();
    }

    @Test
    void springAiRetryIsBoundedAndRetriesTransientButNotBadRequest() {
        RetryTemplate retryTemplate = new GroqChatConfiguration().groqRetryTemplate(new AppProperties());
        int[] transientAttempts = {0};

        String result = retryTemplate.execute(context -> {
            if (++transientAttempts[0] == 1) {
                throw new TransientAiException("temporary failure");
            }
            return "success";
        });

        assertThat(result).isEqualTo("success");
        assertThat(transientAttempts[0]).isEqualTo(2);

        int[] permanentAttempts = {0};
        assertThatThrownBy(() -> retryTemplate.execute(context -> {
            permanentAttempts[0]++;
            throw HttpClientErrorException.create(
                    HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        })).isInstanceOf(HttpClientErrorException.class);
        assertThat(permanentAttempts[0]).isEqualTo(1);
    }

    @Test
    void networkFailureLogsNoRequestOrResponseContent() {
        AppProperties properties = configuredProperties("gsk_test-secret-key");
        ProviderFixture fixture = fixture(
                new ResourceAccessException("timeout echoed prompt diff sourceCode gsk_test-secret-key"), properties);
        Logger logger = (Logger) LoggerFactory.getLogger(GroqLlmReviewProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        ReviewDecision decision;
        try {
            decision = fixture.provider().review(request());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(decision.fallback()).isTrue();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("provider=groq", "httpStatus=unavailable", "exceptionType=ResourceAccessException",
                        "Provider call failed without an HTTP error response", "retryable=true")
                .doesNotContain("gsk_test-secret-key", "echoed prompt", "diff", "sourceCode");
    }

    @ParameterizedTest
    @MethodSource("httpFailureCases")
    void httpFailuresLogSafeDiagnosticsAndReturnFallback(HttpStatus status, boolean retryable, String errorType) {
        String apiKey = "gsk_test-secret-key";
        AppProperties properties = configuredProperties(apiKey);
        String providerError = "temporary provider message; key=" + apiKey
                + "; Authorization: Bearer secret-token; repo acme/payments-api"
                + " diff PaymentController.java prompt Protect payment contract";
        ProviderFixture fixture = fixture(httpException(status, providerError), properties);
        Logger logger = (Logger) LoggerFactory.getLogger(GroqLlmReviewProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        ReviewDecision decision;
        try {
            decision = fixture.provider().review(request());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(decision.fallback()).isTrue();
        assertThat(decision.provider()).isEqualTo("groq");
        assertThat(decision.summary()).isEqualTo("Groq provider failed; using deterministic fallback");
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("provider=groq", "model=openai/gpt-oss-20b", "httpStatus=" + status.value(),
                        "exceptionType=" + errorType, "retryable=" + retryable, "finalFallbackReason=")
                .doesNotContain(apiKey, "secret-token", "acme/payments-api", "PaymentController.java",
                        "Protect payment contract", "failed_generation");
        if (status == HttpStatus.BAD_REQUEST) {
            assertThat(event.getFormattedMessage())
                    .contains("response body withheld", "responseFormatType=JSON_SCHEMA", "strictSetting=true",
                            "riskAlignmentRequired=true", "riskAlignmentNullable=false")
                    .doesNotContain("temporary provider message");
        } else {
            assertThat(event.getFormattedMessage()).contains("temporary provider message");
        }
    }

                @Test
                void badRequestLogsFailedGenerationStructureWithoutGeneratedValues() throws Exception {
                                ObjectMapper mapper = new ObjectMapper();
                                String generatedText = "MODEL_VALUE_SENTINEL";
                                String hiddenFieldValue = "FIELD_VALUE_SENTINEL";
                                String failedGeneration = """
                                                                {
                                                                        "overallAssessment": "%s",
                                                                        "overallRiskLevel": "NOT_A_RISK_LEVEL",
                                                                        "overallRiskScore": "%s",
                                                                        "overallRiskConfidence": 0.8,
                                                                        "overallRiskReason": "MODEL_REASON_SENTINEL",
                                                                        "changeSummary": "MODEL_SUMMARY_SENTINEL",
                                                                        "files": [
                                                                                {
                                                                                        "file": "MODEL_FILE_SENTINEL",
                                                                                        "riskLevel": "NOT_A_RISK_LEVEL",
                                                                                        "riskCategories": ["NOT_A_RISK_CATEGORY"],
                                                                                        "unexpectedPrivateNested": "HIDDEN_NESTED_SENTINEL"
                                                                                }
                                                                        ],
                                                                        "criticalFindings": [],
                                                                        "endpointImpact": [],
                                                                        "unexpectedPrivateProperty": "%s"
                                                                }
                                                                """.formatted(generatedText, hiddenFieldValue, hiddenFieldValue);
                                var errorResponse = mapper.createObjectNode();
                                errorResponse.putObject("error")
                                                                .put("message", "Failed to validate JSON. See failed_generation for more details.")
                                                                .put("failed_generation", failedGeneration);
                                ProviderFixture fixture = fixture(httpExceptionWithBody(HttpStatus.BAD_REQUEST, errorResponse.toString()));
                                Logger logger = (Logger) LoggerFactory.getLogger(GroqLlmReviewProvider.class);
                                ListAppender<ILoggingEvent> appender = new ListAppender<>();
                                appender.start();
                                logger.addAppender(appender);

                                ReviewDecision decision;
                                try {
                                                decision = fixture.provider().review(request());
                                } finally {
                                                logger.detachAppender(appender);
                                                appender.stop();
                                }

                                assertThat(decision.fallback()).isTrue();
                                assertThat(appender.list).hasSize(1);
                                String diagnostic = appender.list.get(0).getFormattedMessage();
                                assertThat(diagnostic)
                                                                .contains("httpStatus=400", "exceptionType=BadRequest", "failedGenerationPresent=true",
                                                                                                "syntaxValid=true", "topLevelType=object", "schemaReferencesInvolved=false",
                                                                                                "topLevelPropertyNames=", "missingRequired=[businessImpactAffectedCapability",
                                                                                                "unexpectedProperties=[unexpectedPrivateProperty, files[0].unexpectedPrivateNested]",
                                                                                                "overallRiskScore:expected=number,actual=string",
                                                                                                "riskAlignmentPresent=false",
                                                                                                "riskAlignmentValid=false", "likelyViolation=missing_required_property",
                                                                                                "files[0].file=string", "files[0].whatItDoes=missing",
                                                                                                "itemCount=1", "itemTypes=[object]")
                                                                .doesNotContain(generatedText, hiddenFieldValue, "MODEL_REASON_SENTINEL",
                                                                                                "MODEL_SUMMARY_SENTINEL", "MODEL_FILE_SENTINEL", "HIDDEN_NESTED_SENTINEL",
                                                                                                "NOT_A_RISK_LEVEL",
                                                                                                "NOT_A_RISK_CATEGORY", "failed_generation", "Failed to validate JSON");
                }

    private static Stream<Arguments> httpFailureCases() {
        return Stream.of(
                Arguments.of(HttpStatus.BAD_REQUEST, false, "BadRequest"),
                Arguments.of(HttpStatus.UNAUTHORIZED, false, "Unauthorized"),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, true, "TooManyRequests"),
                Arguments.of(HttpStatus.BAD_GATEWAY, true, "BadGateway"),
                Arguments.of(HttpStatus.SERVICE_UNAVAILABLE, true, "ServiceUnavailable"));
    }

    private static Stream<Arguments> structuredRiskCategoryCases() {
        return Stream.of(
                                Arguments.of("pure whitespace change", "      ", List.of(GroqReviewOutput.RiskCategory.LOW_IMPACT)),
                                Arguments.of("comment-only change", "// validation relaxed", List.of(GroqReviewOutput.RiskCategory.LOW_IMPACT)),
                                Arguments.of("documentation-only change", "README update", List.of(GroqReviewOutput.RiskCategory.DOCUMENTATION_ONLY)),
                                Arguments.of("local variable rename", "const total = result.length;", List.of(GroqReviewOutput.RiskCategory.LOW_IMPACT)),
                                Arguments.of("return value change", "return response.status;", List.of(GroqReviewOutput.RiskCategory.FUNCTIONAL_REGRESSION)),
                                Arguments.of("removed method", "deleteOrder() { }", List.of(GroqReviewOutput.RiskCategory.RUNTIME_BREAKING, GroqReviewOutput.RiskCategory.FUNCTIONAL_REGRESSION)),
                                Arguments.of("validation logic change", "if (!isValid(input)) throw new IllegalArgumentException();", List.of(GroqReviewOutput.RiskCategory.BUSINESS_LOGIC_CHANGE, GroqReviewOutput.RiskCategory.FUNCTIONAL_REGRESSION)),
                                Arguments.of("authentication or authorization change", "if (user.isAdmin()) permit();", List.of(GroqReviewOutput.RiskCategory.SECURITY_RISK, GroqReviewOutput.RiskCategory.FUNCTIONAL_REGRESSION)),
                                Arguments.of("database or schema change", "ALTER TABLE orders ADD COLUMN tenant_id uuid;", List.of(GroqReviewOutput.RiskCategory.DATA_RISK, GroqReviewOutput.RiskCategory.BUSINESS_LOGIC_CHANGE)),
                                Arguments.of("configuration change", "spring.datasource.url=jdbc:...", List.of(GroqReviewOutput.RiskCategory.RUNTIME_BREAKING, GroqReviewOutput.RiskCategory.BUSINESS_LOGIC_CHANGE)),
                                Arguments.of("dependency or import change", "import foo from 'new-lib';", List.of(GroqReviewOutput.RiskCategory.BUILD_BREAKING)));
    }

    private static RuntimeException httpException(HttpStatus status, String message) {
        byte[] responseBody = ("{\"error\":{\"message\":\"" + message + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
                return httpExceptionWithBody(status, new String(responseBody, StandardCharsets.UTF_8));
        }

        private static RuntimeException httpExceptionWithBody(HttpStatus status, String body) {
                byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
        if (status.is4xxClientError()) {
            return HttpClientErrorException.create(
                    status, status.getReasonPhrase(), HttpHeaders.EMPTY, responseBody, StandardCharsets.UTF_8);
        }
        return HttpServerErrorException.create(
                status, status.getReasonPhrase(), HttpHeaders.EMPTY, responseBody, StandardCharsets.UTF_8);
    }

    private static GroqReviewOutput analysis(
            GroqReviewOutput.RiskLevel level,
            double score,
            String overallAssessment,
            List<GroqReviewOutput.CriticalFinding> criticalFindings,
            List<GroqReviewOutput.FileAnalysis> files) {
        List<GroqReviewOutput.EndpointImpact> endpointImpacts = files.stream()
                .flatMap(file -> file.affectedEndpoints().stream())
                .distinct()
                .toList();
        return new GroqReviewOutput(
                overallAssessment,
                new GroqReviewOutput.OverallRisk(level, score, 0.98, "Grounded in the supplied diff."),
                "Update PR summary",
                files,
                criticalFindings,
                endpointImpacts,
                new GroqReviewOutput.BusinessImpact(
                        "Business value cannot be determined from the provided diff/context.", "Unknown", 0.2),
                "Review the affected code before merge.",
                0.98,
                new GroqReviewOutput.RiskAlignment(
                        level.name(), level.name(), GroqReviewOutput.RiskAlignment.Alignment.ALIGNED,
                        "The advisory and deterministic risk levels agree."));
    }

    private static GroqStructuredReviewOutput toStructuredReviewOutput(GroqReviewOutput output) {
        List<GroqStructuredReviewOutput.FileAnalysis> files = output.files().stream()
                .map(file -> new GroqStructuredReviewOutput.FileAnalysis(
                        file.file(), file.changeSummary(), file.whatChanged(), file.whatItDoes(),
                        file.businessImpact(), file.riskLevel() == null ? null : file.riskLevel().name(),
                        file.riskCategories().stream().map(category -> category == null ? null : category.name()).toList(),
                        file.technicalImpact(), file.evidence(), file.confidence()))
                .toList();
        List<GroqStructuredReviewOutput.CriticalFinding> findings = output.criticalFindings().stream()
                .map(finding -> new GroqStructuredReviewOutput.CriticalFinding(
                        finding.severity() == null ? null : finding.severity().name(),
                        finding.category() == null ? null : finding.category().name(),
                        finding.file(), finding.finding(), finding.whyItMatters(), finding.evidence(),
                        finding.recommendedAction()))
                .toList();
        List<GroqStructuredReviewOutput.EndpointImpact> endpoints = new java.util.ArrayList<>();
        for (GroqReviewOutput.FileAnalysis file : output.files()) {
            for (GroqReviewOutput.EndpointImpact endpoint : file.affectedEndpoints()) {
                endpoints.add(new GroqStructuredReviewOutput.EndpointImpact(
                        file.file(), endpoint.method(), endpoint.path(), endpoint.criticality(),
                        endpoint.impact(), endpoint.reason()));
            }
        }
        for (GroqReviewOutput.EndpointImpact endpoint : output.endpointImpact()) {
            boolean associatedWithFile = output.files().stream()
                    .anyMatch(file -> file.affectedEndpoints().contains(endpoint));
            if (!associatedWithFile) {
                endpoints.add(new GroqStructuredReviewOutput.EndpointImpact(
                        "", endpoint.method(), endpoint.path(), endpoint.criticality(),
                        endpoint.impact(), endpoint.reason()));
            }
        }
        GroqReviewOutput.RiskAlignment riskAlignment = output.riskAlignment();
        String riskLevel = output.overallRisk() == null || output.overallRisk().level() == null
                ? "LOW" : output.overallRisk().level().name();
        return new GroqStructuredReviewOutput(
                output.overallAssessment(),
                output.overallRisk() == null || output.overallRisk().level() == null
                        ? null : output.overallRisk().level().name(),
                output.overallRisk() == null ? null : output.overallRisk().score(),
                output.overallRisk() == null ? null : output.overallRisk().confidence(),
                output.overallRisk() == null ? null : output.overallRisk().reason(),
                output.changeSummary(), files, findings, endpoints,
                output.businessImpact() == null ? null : output.businessImpact().summary(),
                output.businessImpact() == null ? null : output.businessImpact().affectedCapability(),
                output.businessImpact() == null ? null : output.businessImpact().confidence(),
                output.recommendation(), output.reviewConfidence(),
                riskAlignment == null || riskAlignment.deterministicRisk() == null
                        ? riskLevel : riskAlignment.deterministicRisk(),
                riskAlignment == null || riskAlignment.llmRisk() == null
                        ? riskLevel : riskAlignment.llmRisk(),
                riskAlignment == null || riskAlignment.alignment() == null
                        ? "ALIGNED" : riskAlignment.alignment().name(),
                riskAlignment == null || riskAlignment.explanation() == null
                        ? "The advisory and deterministic risk levels agree." : riskAlignment.explanation());
    }

    private static AppProperties configuredProperties(String apiKey) {
        AppProperties properties = new AppProperties();
        properties.getLlm().getGroq().setApiKey(apiKey);
        return properties;
    }

    private static ProviderFixture fixture(Object response) {
        return fixture(response, configuredProperties("test-groq-key"));
    }

    private static ProviderFixture fixture(Object response, AppProperties properties) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
                if (response instanceof Exception exception) {
                        when(responseSpec.entity(any(BeanOutputConverter.class))).thenThrow(exception);
                        when(responseSpec.entity(any(Class.class))).thenThrow(exception);
        } else {
                        GroqStructuredReviewOutput structuredResponse = toStructuredReviewOutput((GroqReviewOutput) response);
                        when(responseSpec.entity(any(BeanOutputConverter.class))).thenReturn(structuredResponse);
                        when(responseSpec.entity(any(Class.class))).thenReturn(structuredResponse);
        }
        return new ProviderFixture(new GroqLlmReviewProvider(chatClient, properties, new ObjectMapper()), requestSpec);
    }

    private static ReviewRequest request() {
        return new ReviewRequest(
                "acme/payments-api",
                "Protect payment contract",
                "diff --git a/src/main/java/PaymentController.java",
                "GET /api/payments; deterministic tier MEDIUM",
                64.0);
    }

    private record ProviderFixture(
            GroqLlmReviewProvider provider,
            ChatClient.ChatClientRequestSpec requestSpec) {
    }
}
