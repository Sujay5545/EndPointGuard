package com.endpointguard.review.provider;

import com.endpointguard.common.config.AppProperties;
import com.endpointguard.review.dto.GroqReviewOutput;
import com.endpointguard.review.dto.GroqStructuredReviewOutput;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
@Slf4j
public class GroqLlmReviewProvider implements LlmReviewProvider {

    private static final int MAX_RISK_CATEGORIES_PER_FILE = 3;
    private static final Pattern CREDENTIAL_HEADER = Pattern.compile(
            "(?i)(authorization|x-api-key)\\s*[:=]\\s*[^\\s,;]+(?:\\s+[^\\s,;]+)?");

    private final ChatClient chatClient;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public GroqLlmReviewProvider(ChatClient chatClient, AppProperties appProperties, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "groq";
    }

    @Override
    public ReviewDecision review(ReviewRequest request) {
        AppProperties.Llm.Groq config = appProperties.getLlm().getGroq();
        String model = config.getModel();
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return fallback("Groq API key not configured; using deterministic fallback");
        }

        try {
            String changedFiles = request.changedFiles().isEmpty()
                ? objectMapper.writeValueAsString(List.of(request.diff()))
                : objectMapper.writeValueAsString(request.changedFiles());
            String pullRequestDescription = request.pullRequestDescription().isBlank()
                    ? "unavailable"
                    : request.pullRequestDescription();
            GroqStructuredReviewOutput structuredOutput = chatClient.prompt()
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .system("""
                    You are performing a production code review, not a generic PR summary. Analyze correctness before summarizing business impact, in this order:
                    1. Syntax and likely build/compile correctness.
                    2. Runtime correctness and control flow.
                    3. Functional behavior and regressions.
                    4. API behavior and affected endpoint impact.
                    5. Security, data, and performance impact.
                    6. Business impact and overall risk.

                    Carefully inspect removed and changed tokens. Removing a return, required delimiter, import, type, method, validation, or authorization check can be high or critical risk even in a one-line or frontend-only diff. Never call a change formatting-only if it can alter syntax, control flow, return values, runtime behavior, configuration, or business logic. Absence of an API change does not imply safety. Diff size does not determine risk.

                    Analyze every supplied file independently. Ground important findings in exact changed-line evidence. Explain what changed, what the code does, likely functional consequences, affected endpoints, risk categories, and recommended actions. Every file must have 1 to 3 riskCategories, even low-risk or documentation-only files. Use only these exact values: BUILD_BREAKING, RUNTIME_BREAKING, FUNCTIONAL_REGRESSION, API_BEHAVIOR_CHANGE, SECURITY_RISK, DATA_RISK, PERFORMANCE_RISK, BUSINESS_LOGIC_CHANGE, LOW_IMPACT, DOCUMENTATION_ONLY, FORMATTING_ONLY. Do not use an empty array, null, duplicates, or invented categories. Choose the most appropriate category for every file. Do not invent requirements or business value. If business value is unclear, say exactly: "Business value cannot be determined from the provided diff/context." Distinguish an LLM-inferred build/runtime risk from a verified build failure; no build or static analysis was run for this review. Note when surrounding source or context is unavailable.

                    Follow the flat schema exactly: provide overall-risk fields, business-impact fields, and risk-alignment fields at the top level. Keep endpointImpact as a flat top-level array; include sourceFile on each endpoint impact and use the matching files[].file path. Do not nest endpoint impacts inside file entries.

                    Treat deterministic EndpointGuard risk as authoritative and separate. The LLM assessment is advisory and must not modify it. Always return a non-null riskAlignment object with deterministicRisk, llmRisk, alignment, and explanation, even when both assessments agree. Use exact existing EndpointGuard risk-level tokens for deterministicRisk and llmRisk: LOW, MEDIUM, HIGH, CRITICAL. Use exact alignment strings: ALIGNED or DIFFERENT. Do not emit phrases like HIGH RISK, medium risk, or any other wording. Set alignment to ALIGNED when the risk levels agree. Set it to DIFFERENT when the advisory risk is higher, lower, or otherwise differs, and explicitly explain the direction and reason. For ALIGNED, briefly explain the agreement. The structured result must match the requested schema and allowed enum values.
                    """)
                .user("""
                    Repository: %s
                    PR title/context: %s
                    PR description/body: %s
                    Changed files with path, line counts, and patch (JSON):
                    %s
                    Affected endpoints and deterministic risk context: %s
                    Deterministic EndpointGuard risk score (0-100): %s
                    Surrounding source context: unavailable; no repository checkout or source fetch was performed.
                    Build/test/lint evidence: unavailable; no results were supplied for this review.

                    Return an overall assessment and change summary; overall risk level, score, confidence, and reason; per-file analysis with 1 to 3 valid riskCategories on every file; critical findings; endpoint impact; business impact; recommendation; review confidence; and all risk-alignment fields on every response. Use empty arrays when there are no findings except riskCategories, which must contain 1 to 3 allowed values for each file. Do not state that a build failed unless a build result was supplied.
                    """.formatted(request.repository(), request.changeSummary(), pullRequestDescription, changedFiles,
                    request.affectedEndpoints(), request.computedRiskScore()))
                    .call()
                        .entity(new GroqStructuredReviewConverter());

                    GroqReviewOutput output = mapStructuredOutput(structuredOutput);

            if (!isValid(output)) {
                log.warn("LLM review fallback: provider={} model={} httpStatus=unavailable "
                        + "exceptionType=InvalidStructuredResponse validation={} retryable=false "
                                + "finalFallbackReason=invalid_structured_response",
                    providerName(), model, validationDiagnostics(output));
                return fallback("Groq response was invalid; using deterministic fallback");
            }
                List<String> findings = output.criticalFindings().stream()
                    .map(finding -> finding.severity() + " " + finding.category() + " in " + finding.file()
                        + ": " + finding.finding() + " Evidence: " + finding.evidence())
                    .limit(50)
                    .toList();
            return new ReviewDecision(
                    output.overallRisk().level().name(),
                    output.overallRisk().score(),
                    output.overallAssessment().trim(),
                    findings,
                    providerName(),
                    false,
                    output);
        } catch (Exception exception) {
            RestClientResponseException responseException = findResponseException(exception);
            Integer httpStatus = responseException == null ? null : responseException.getStatusCode().value();
            boolean retryable = isRetryable(exception, httpStatus);
            boolean structuredOutputRejected = httpStatus != null && httpStatus == 400;
            Map<String, Object> safeSchemaValidation = Map.of("available", false);
            if (structuredOutputRejected) {
                try {
                    safeSchemaValidation = analyzeFailedGeneration(
                            responseException.getResponseBodyAsString(), objectMapper);
                } catch (Exception diagnosticFailure) {
                    safeSchemaValidation = Map.of(
                            "available", false,
                            "diagnosticFailureType", diagnosticFailure.getClass().getSimpleName());
                }
            }
            log.warn("LLM review fallback: provider={} model={} httpStatus={} exceptionType={} message={} "
                        + "schemaValidation={} retryable={} finalFallbackReason={}",
                    providerName(), model, httpStatus == null ? "unavailable" : httpStatus,
                    responseException == null ? exception.getClass().getSimpleName()
                            : responseException.getClass().getSimpleName(),
                    structuredOutputRejected ? "Groq rejected structured JSON; response body withheld"
                            : safeExceptionMessage(exception, responseException, apiKey, request),
                    structuredOutputRejected ? safeSchemaValidation : "not_applicable", retryable,
                    failureCategory(exception, httpStatus));
            return fallback("Groq provider failed; using deterministic fallback");
        }
    }

    static Map<String, Object> structuredSchemaDiagnostics(ObjectMapper objectMapper) {
        BeanOutputConverter<GroqStructuredReviewOutput> converter = new GroqStructuredReviewConverter();
        JsonNode schema = objectMapper.valueToTree(converter.getJsonSchemaMap());
        JsonNode properties = schema.path("properties");
        JsonNode required = schema.path("required");
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setOutputSchema(converter.getJsonSchema());
        var responseFormat = options.getResponseFormat();
        var jsonSchema = responseFormat == null ? null : responseFormat.getJsonSchema();

        List<String> propertyNames = new ArrayList<>();
        if (properties.isObject()) {
            properties.fieldNames().forEachRemaining(propertyNames::add);
            propertyNames.sort(String::compareTo);
        }
        Set<String> schemaFeatures = new TreeSet<>();
        collectSchemaFeatures(schema, schemaFeatures);
        boolean additionalPropertiesFalse = allObjectsForbidAdditionalProperties(schema);
        boolean riskAlignmentRequired = required.isArray() && containsText(required, "riskAlignment");
        boolean riskAlignmentNullable = isNullable(properties.path("riskAlignment"));
        if (jsonSchema == null || !Boolean.TRUE.equals(jsonSchema.getStrict())) {
            schemaFeatures.add("strict_not_true");
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("responseFormatType", responseFormat == null || responseFormat.getType() == null
                ? "unavailable" : responseFormat.getType().name());
        diagnostics.put("strictSetting", jsonSchema == null ? "unavailable" : jsonSchema.getStrict());
        diagnostics.put("schemaTopLevelType", schema.path("type").asText("unavailable"));
        diagnostics.put("propertyCount", properties.isObject() ? properties.size() : 0);
        diagnostics.put("requiredPropertiesCount", required.isArray() ? required.size() : 0);
        diagnostics.put("enumFieldsCount", countFeature(schema, "enum"));
        diagnostics.put("nestedObjectArrayFieldsCount", countNestedObjectArrayFields(schema));
        diagnostics.put("additionalPropertiesFalse", additionalPropertiesFalse);
        diagnostics.put("propertyNames", propertyNames);
        JsonNode riskCategoriesSchema = findRiskCategoriesSchema(schema);
        diagnostics.put("riskCategoriesMinItems", riskCategoriesSchema.path("minItems").asInt(-1));
        diagnostics.put("riskCategoriesMaxItems", riskCategoriesSchema.path("maxItems").asInt(-1));
        diagnostics.put("riskAlignmentRequired", riskAlignmentRequired);
        diagnostics.put("riskAlignmentNullable", riskAlignmentNullable);
        diagnostics.put("schemaFeaturesToReview", new ArrayList<>(schemaFeatures));
        return diagnostics;
    }

    static Map<String, Object> analyzeFailedGeneration(String responseBody, ObjectMapper objectMapper) {
        Map<String, Object> diagnostics = new LinkedHashMap<>(structuredSchemaDiagnostics(objectMapper));
        List<String> missingRequired = new ArrayList<>();
        List<String> unexpectedProperties = new ArrayList<>();
        List<String> invalidTypes = new ArrayList<>();
        List<String> invalidEnums = new ArrayList<>();
        List<String> invalidNumericConstraints = new ArrayList<>();
        List<String> invalidNestedFields = new ArrayList<>();
        Map<String, Object> arrays = new LinkedHashMap<>();
        Map<String, String> propertyTypes = new LinkedHashMap<>();

        JsonNode errorPayload;
        try {
            errorPayload = objectMapper.readTree(responseBody);
            diagnostics.put("errorBodySyntaxValid", errorPayload != null);
        } catch (Exception ignored) {
            diagnostics.put("errorBodySyntaxValid", false);
            diagnostics.put("failedGenerationPresent", false);
            diagnostics.put("syntaxValid", false);
            diagnostics.put("topLevelType", "unavailable");
            return finishSchemaValidation(diagnostics, missingRequired, unexpectedProperties, invalidTypes,
                    invalidEnums, invalidNumericConstraints, invalidNestedFields, arrays,
                    propertyTypes, false, false);
        }

        JsonNode failedGeneration = errorPayload == null ? null : errorPayload.path("error").path("failed_generation");
        if (failedGeneration == null || failedGeneration.isMissingNode() || failedGeneration.isNull()) {
            failedGeneration = errorPayload == null ? null : errorPayload.path("failed_generation");
        }
        boolean present = failedGeneration != null && !failedGeneration.isMissingNode() && !failedGeneration.isNull();
        diagnostics.put("failedGenerationPresent", present);
        if (!present) {
            diagnostics.put("syntaxValid", false);
            diagnostics.put("topLevelType", "unavailable");
            return finishSchemaValidation(diagnostics, missingRequired, unexpectedProperties, invalidTypes,
                    invalidEnums, invalidNumericConstraints, invalidNestedFields, arrays,
                    propertyTypes, false, false);
        }

        JsonNode generatedJson;
        if (failedGeneration.isTextual()) {
            try {
                generatedJson = objectMapper.readTree(failedGeneration.asText());
            } catch (Exception ignored) {
                diagnostics.put("syntaxValid", false);
                diagnostics.put("topLevelType", "unavailable");
                return finishSchemaValidation(diagnostics, missingRequired, unexpectedProperties, invalidTypes,
                    invalidEnums, invalidNumericConstraints, invalidNestedFields, arrays,
                    propertyTypes, false, false);
            }
        } else {
            generatedJson = failedGeneration;
        }

        diagnostics.put("syntaxValid", generatedJson != null);
        diagnostics.put("topLevelType", jsonType(generatedJson));
        JsonNode schema = objectMapper.valueToTree(new GroqStructuredReviewConverter().getJsonSchemaMap());
        JsonNode schemaProperties = schema.path("properties");
        JsonNode required = schema.path("required");
        boolean schemaReferencesInvolved = schema.has("$defs") || countFeature(schema, "$ref") > 0;
        diagnostics.put("schemaReferencesInvolved", schemaReferencesInvolved);

        Set<String> expectedNames = new TreeSet<>();
        if (schemaProperties.isObject()) {
            schemaProperties.fieldNames().forEachRemaining(expectedNames::add);
        }
        if (generatedJson != null && generatedJson.isObject()) {
            List<String> generatedPropertyNames = new ArrayList<>();
            generatedJson.fieldNames().forEachRemaining(name -> {
                generatedPropertyNames.add(name);
                if (!expectedNames.contains(name)) {
                    unexpectedProperties.add(name);
                }
            });
            generatedPropertyNames.sort(String::compareTo);
            diagnostics.put("topLevelPropertyNames", generatedPropertyNames);
        } else {
            diagnostics.put("topLevelPropertyNames", List.of());
        }
        for (String requiredName : textValues(required)) {
            if (generatedJson == null || !generatedJson.isObject() || !generatedJson.has(requiredName)) {
                missingRequired.add(requiredName);
            }
        }
        if (generatedJson != null && generatedJson.isObject() && schemaProperties.isObject()) {
            var fields = schemaProperties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String propertyName = field.getKey();
                JsonNode actual = generatedJson.get(propertyName);
                propertyTypes.put(propertyName, actual == null ? "missing" : jsonType(actual));
                if (actual != null) {
                    inspectAgainstSchema(field.getValue(), actual, propertyName, schema,
                            missingRequired, unexpectedProperties, invalidTypes, invalidEnums,
                            invalidNumericConstraints, invalidNestedFields, arrays, propertyTypes);
                }
            }
        } else if (generatedJson != null) {
            invalidTypes.add("$:expected=object,actual=" + jsonType(generatedJson));
        }

        JsonNode riskAlignment = generatedJson != null && generatedJson.isObject()
                ? generatedJson.get("riskAlignment") : null;
        boolean riskAlignmentPresent = riskAlignment != null && !riskAlignment.isNull();
        boolean riskAlignmentValid = riskAlignmentPresent
                && !hasPathPrefix(invalidTypes, "riskAlignment")
                && !hasPathPrefix(invalidEnums, "riskAlignment")
                && !hasPathPrefix(missingRequired, "riskAlignment");
        diagnostics.put("riskAlignmentPresent", riskAlignmentPresent);
        return finishSchemaValidation(diagnostics, missingRequired, unexpectedProperties, invalidTypes,
                invalidEnums, invalidNumericConstraints, invalidNestedFields, arrays,
            propertyTypes, riskAlignmentPresent, riskAlignmentValid);
    }

    private static Map<String, Object> finishSchemaValidation(
            Map<String, Object> diagnostics,
            List<String> missingRequired,
            List<String> unexpectedProperties,
            List<String> invalidTypes,
            List<String> invalidEnums,
            List<String> invalidNumericConstraints,
            List<String> invalidNestedFields,
            Map<String, Object> arrays,
            Map<String, String> propertyTypes,
            boolean riskAlignmentPresent,
            boolean riskAlignmentValid) {
        diagnostics.put("missingRequired", missingRequired);
        diagnostics.put("unexpectedProperties", unexpectedProperties);
        diagnostics.put("invalidTypes", invalidTypes);
        diagnostics.put("invalidEnums", invalidEnums);
        diagnostics.put("invalidNumericConstraints", invalidNumericConstraints);
        diagnostics.put("invalidNestedFields", invalidNestedFields);
        diagnostics.put("arrays", arrays);
        diagnostics.put("propertyTypes", propertyTypes);
        diagnostics.put("riskAlignmentPresent", riskAlignmentPresent);
        diagnostics.put("riskAlignmentValid", riskAlignmentValid);
        diagnostics.put("likelyViolation", firstViolation(missingRequired, invalidTypes, invalidEnums,
                invalidNumericConstraints, invalidNestedFields, unexpectedProperties,
                Boolean.TRUE.equals(diagnostics.get("syntaxValid"))));
        return diagnostics;
    }

    private static String firstViolation(
            List<String> missingRequired,
            List<String> invalidTypes,
            List<String> invalidEnums,
            List<String> invalidNumericConstraints,
            List<String> invalidNestedFields,
            List<String> unexpectedProperties,
            boolean syntaxValid) {
        if (!syntaxValid) return "generated_json_not_valid";
        if (!missingRequired.isEmpty()) return "missing_required_property";
        if (!invalidTypes.isEmpty()) return "wrong_json_type";
        if (!invalidEnums.isEmpty()) return "invalid_enum";
        if (!invalidNumericConstraints.isEmpty()) return "numeric_constraint";
        if (!invalidNestedFields.isEmpty()) return "invalid_nested_structure";
        if (!unexpectedProperties.isEmpty()) return "unexpected_property";
        return "no_structural_violation_detected";
    }

    private static void inspectAgainstSchema(
            JsonNode originalSchema,
            JsonNode actual,
            String path,
            JsonNode rootSchema,
            List<String> missingRequired,
            List<String> unexpectedProperties,
            List<String> invalidTypes,
            List<String> invalidEnums,
            List<String> invalidNumericConstraints,
            List<String> invalidNestedFields,
            Map<String, Object> arrays,
            Map<String, String> propertyTypes) {
        JsonNode schema = resolveSchema(originalSchema, rootSchema);
        JsonNode expectedType = schema.path("type");
        String actualType = jsonType(actual);
        if (!expectedType.isMissingNode() && !matchesType(expectedType, actualType)) {
            invalidTypes.add(path + ":expected=" + expectedTypeName(expectedType) + ",actual=" + actualType);
            if (!path.equals("riskAlignment")) {
                invalidNestedFields.add(path);
            }
            return;
        }

        JsonNode enumValues = schema.path("enum");
        boolean enumValid = !enumValues.isArray() || enumValues.isEmpty();
        if (enumValues.isArray()) {
            for (JsonNode enumValue : enumValues) {
                if (enumValue.equals(actual)) {
                    enumValid = true;
                    break;
                }
            }
        }
        if (!enumValid) {
            invalidEnums.add(path);
        }
        if (actual.isNumber()) {
            if ((schema.has("minimum") && actual.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0)
                    || (schema.has("maximum") && actual.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0)
                    || (schema.has("exclusiveMinimum") && actual.decimalValue().compareTo(schema.path("exclusiveMinimum").decimalValue()) <= 0)
                    || (schema.has("exclusiveMaximum") && actual.decimalValue().compareTo(schema.path("exclusiveMaximum").decimalValue()) >= 0)) {
                invalidNumericConstraints.add(path);
            }
            if ((path.equals("overallRisk.score") && !validScore(actual.doubleValue()))
                    || ((path.equals("overallRisk.confidence") || path.equals("reviewConfidence")
                    || path.equals("businessImpact.confidence") || path.endsWith(".confidence"))
                    && !validConfidence(actual.doubleValue()))) {
                invalidNumericConstraints.add(path);
            }
        }

        if (actual.isArray()) {
            Set<String> itemTypes = new TreeSet<>();
            for (int index = 0; index < actual.size(); index++) {
                JsonNode item = actual.get(index);
                itemTypes.add(jsonType(item));
                inspectAgainstSchema(schema.path("items"), item, path + "[" + index + "]", rootSchema,
                    missingRequired, unexpectedProperties, invalidTypes, invalidEnums,
                    invalidNumericConstraints, invalidNestedFields, arrays, propertyTypes);
            }
            Map<String, Object> arrayInfo = new LinkedHashMap<>();
            arrayInfo.put("itemCount", actual.size());
            arrayInfo.put("itemTypes", new ArrayList<>(itemTypes));
            arrays.put(path, arrayInfo);
        }
        if (actual.isObject()) {
            JsonNode properties = schema.path("properties");
            if (properties.isObject()) {
                Set<String> expectedPropertyNames = new TreeSet<>();
                properties.fieldNames().forEachRemaining(expectedPropertyNames::add);
                actual.fieldNames().forEachRemaining(name -> {
                    if (!expectedPropertyNames.contains(name)) {
                        unexpectedProperties.add(path + "." + name);
                    }
                });
            }
            for (String requiredName : textValues(schema.path("required"))) {
                if (!actual.has(requiredName)) {
                    missingRequired.add(path + "." + requiredName);
                    invalidNestedFields.add(path + "." + requiredName);
                }
            }
            if (properties.isObject()) {
                var fields = properties.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> property = fields.next();
                    JsonNode value = actual.get(property.getKey());
                    propertyTypes.put(path + "." + property.getKey(),
                            value == null ? "missing" : jsonType(value));
                    if (value != null) {
                        inspectAgainstSchema(property.getValue(), value, path + "." + property.getKey(),
                                rootSchema, missingRequired, unexpectedProperties, invalidTypes,
                                invalidEnums, invalidNumericConstraints, invalidNestedFields,
                                arrays, propertyTypes);
                    }
                }
            }
        }
    }

    private static JsonNode resolveSchema(JsonNode schema, JsonNode rootSchema) {
        JsonNode reference = schema.path("$ref");
        if (!reference.isTextual() || !reference.asText().startsWith("#/")) {
            return schema;
        }
        String pointer = reference.asText().substring(1).replace("~1", "/").replace("~0", "~");
        JsonNode resolved = rootSchema.at(pointer);
        return resolved.isMissingNode() ? schema : resolved;
    }

    private static boolean matchesType(JsonNode expected, String actualType) {
        if (expected.isArray()) {
            return containsText(expected, actualType);
        }
        return expected.asText().equals(actualType);
    }

    private static String expectedTypeName(JsonNode expected) {
        if (expected.isArray()) {
            return String.join("|", textValues(expected));
        }
        return expected.asText();
    }

    private static String jsonType(JsonNode node) {
        if (node == null || node.isMissingNode()) return "missing";
        if (node.isNull()) return "null";
        if (node.isTextual()) return "string";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        return "unknown";
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode value : array) {
                if (value.isTextual()) values.add(value.asText());
            }
        }
        return values;
    }

    private static boolean hasPathPrefix(List<String> paths, String prefix) {
        return paths.stream().anyMatch(path -> path.equals(prefix) || path.startsWith(prefix + ".")
                || path.startsWith(prefix + "["));
    }

    private static void collectSchemaFeatures(JsonNode node, Set<String> features) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                if (Set.of("$schema", "$defs", "$ref", "anyOf", "oneOf", "allOf", "not",
                        "patternProperties", "unevaluatedProperties", "format", "pattern",
                        "multipleOf", "propertyNames", "uniqueItems").contains(name)) {
                    features.add(name);
                }
            });
            JsonNode type = node.path("type");
            if (type.isArray()) {
                features.add("type_union");
            }
            node.elements().forEachRemaining(child -> collectSchemaFeatures(child, features));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectSchemaFeatures(child, features));
        }
    }

    private static boolean allObjectsForbidAdditionalProperties(JsonNode node) {
        if (node.isObject()) {
            boolean objectSchema = node.path("type").asText().equals("object") || node.has("properties");
            if (objectSchema && (!node.path("additionalProperties").isBoolean()
                    || node.path("additionalProperties").asBoolean())) {
                return false;
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                if (!allObjectsForbidAdditionalProperties(fields.next().getValue())) {
                    return false;
                }
            }
        } else if (node.isArray()) {
            var elements = node.elements();
            while (elements.hasNext()) {
                if (!allObjectsForbidAdditionalProperties(elements.next())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean containsText(JsonNode array, String expected) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode value : array) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNullable(JsonNode schema) {
        if (schema.isMissingNode() || schema.isNull()) {
            return false;
        }
        if (schema.path("type").isArray() && containsText(schema.path("type"), "null")) {
            return true;
        }
        for (String combinator : List.of("anyOf", "oneOf")) {
            JsonNode alternatives = schema.path(combinator);
            if (alternatives.isArray()) {
                for (JsonNode alternative : alternatives) {
                    if (alternative.path("type").asText().equals("null")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static JsonNode findRiskCategoriesSchema(JsonNode node) {
        if (node.isObject()) {
            JsonNode properties = node.path("properties");
            JsonNode riskCategories = properties.path("riskCategories");
            if (riskCategories.isObject() && riskCategories.path("type").asText().equals("array")) {
                return riskCategories;
            }
            var fields = node.elements();
            while (fields.hasNext()) {
                JsonNode found = findRiskCategoriesSchema(fields.next());
                if (!found.isMissingNode()) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            var elements = node.elements();
            while (elements.hasNext()) {
                JsonNode found = findRiskCategoriesSchema(elements.next());
                if (!found.isMissingNode()) {
                    return found;
                }
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static int countFeature(JsonNode node, String feature) {
        int count = node.isObject() && node.has(feature) ? 1 : 0;
        if (node.isObject()) {
            var fields = node.elements();
            while (fields.hasNext()) {
                count += countFeature(fields.next(), feature);
            }
        } else if (node.isArray()) {
            var elements = node.elements();
            while (elements.hasNext()) {
                count += countFeature(elements.next(), feature);
            }
        }
        return count;
    }

    private static int countNestedObjectArrayFields(JsonNode node) {
        int count = 0;
        if (node.isObject()) {
            JsonNode properties = node.path("properties");
            if (properties.isObject()) {
                var fields = properties.fields();
                while (fields.hasNext()) {
                    JsonNode property = fields.next().getValue();
                    JsonNode type = property.path("type");
                    if (property.has("$ref") || type.asText().equals("object") || type.asText().equals("array")
                            || (type.isArray() && (containsText(type, "object") || containsText(type, "array")))) {
                        count++;
                    }
                    count += countNestedObjectArrayFields(property);
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!entry.getKey().equals("properties")) {
                    count += countNestedObjectArrayFields(entry.getValue());
                }
            }
        } else if (node.isArray()) {
            var elements = node.elements();
            while (elements.hasNext()) {
                count += countNestedObjectArrayFields(elements.next());
            }
        }
        return count;
    }

        static GroqReviewOutput mapStructuredOutput(GroqStructuredReviewOutput output) {
        if (output == null) {
            return null;
        }

        List<GroqReviewOutput.EndpointImpact> endpointImpacts = output.endpointImpact().stream()
            .map(endpoint -> new GroqReviewOutput.EndpointImpact(
                endpoint.method(), endpoint.path(), endpoint.criticality(), endpoint.impact(), endpoint.reason()))
            .toList();
        List<GroqReviewOutput.EndpointImpact> uniqueEndpointImpacts = endpointImpacts.stream().distinct().toList();
        Map<String, List<GroqReviewOutput.EndpointImpact>> endpointsByFile = new LinkedHashMap<>();
        for (int index = 0; index < output.endpointImpact().size(); index++) {
            GroqStructuredReviewOutput.EndpointImpact endpoint = output.endpointImpact().get(index);
            if (endpoint.sourceFile() != null && !endpoint.sourceFile().isBlank()) {
            endpointsByFile.computeIfAbsent(endpoint.sourceFile(), ignored -> new ArrayList<>())
                .add(endpointImpacts.get(index));
            }
        }

        List<GroqReviewOutput.FileAnalysis> files = output.files().stream()
            .map(file -> new GroqReviewOutput.FileAnalysis(
                file.file(), file.changeSummary(), file.whatChanged(), file.whatItDoes(),
                file.businessImpact(), GroqReviewOutput.RiskLevel.fromString(file.riskLevel()),
                mapRiskCategories(file.riskCategories()),
                file.technicalImpact(), file.evidence(),
                endpointsByFile.getOrDefault(file.file(), List.of()), file.confidence()))
            .toList();
        List<GroqReviewOutput.CriticalFinding> criticalFindings = output.criticalFindings().stream()
            .map(finding -> new GroqReviewOutput.CriticalFinding(
                GroqReviewOutput.RiskLevel.fromString(finding.severity()),
                GroqReviewOutput.RiskCategory.fromString(finding.category()),
                finding.file(), finding.finding(), finding.whyItMatters(), finding.evidence(),
                finding.recommendedAction()))
            .toList();
        GroqReviewOutput.RiskAlignment riskAlignment = new GroqReviewOutput.RiskAlignment(
            output.riskAlignmentDeterministicRisk(), output.riskAlignmentLlmRisk(),
            GroqReviewOutput.RiskAlignment.Alignment.fromString(output.riskAlignment()),
            output.riskAlignmentExplanation());

        return new GroqReviewOutput(
            output.overallAssessment(),
            new GroqReviewOutput.OverallRisk(
                GroqReviewOutput.RiskLevel.fromString(output.overallRiskLevel()),
                output.overallRiskScore(), output.overallRiskConfidence(), output.overallRiskReason()),
            output.changeSummary(), files, criticalFindings, uniqueEndpointImpacts,
            new GroqReviewOutput.BusinessImpact(output.businessImpactSummary(),
                output.businessImpactAffectedCapability(), output.businessImpactConfidence()),
            output.recommendation(), output.reviewConfidence(), riskAlignment);
        }

    static List<GroqReviewOutput.RiskCategory> mapRiskCategories(List<String> categoryNames) {
        return categoryNames == null ? List.of() : categoryNames.stream()
                .map(GroqReviewOutput.RiskCategory::fromString)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

        public static boolean isValid(GroqReviewOutput output) {
        if (output == null || blankOrLong(output.overallAssessment(), 4000)
                || blankOrLong(output.changeSummary(), 4000)
                || blankOrLong(output.recommendation(), 2000)
                || output.overallRisk() == null
                || output.overallRisk().level() == null
                || !validScore(output.overallRisk().score())
                || !validConfidence(output.overallRisk().confidence())
                || !validConfidence(output.reviewConfidence())
                || blankOrLong(output.overallRisk().reason(), 2000)
                || output.businessImpact() == null
                || blankOrLong(output.businessImpact().summary(), 2000)
                || blankOrLong(output.businessImpact().affectedCapability(), 1000)
                || !validConfidence(output.businessImpact().confidence())
                || output.files() == null || output.files().isEmpty() || output.files().size() > 25
                || output.criticalFindings() == null || output.criticalFindings().size() > 50
                || output.endpointImpact() == null || output.endpointImpact().size() > 100
                || (output.riskAlignment() != null && !isValidRiskAlignment(output.riskAlignment()))) {
            return false;
        }
        return output.files().stream().allMatch(GroqLlmReviewProvider::isValidFile)
                && output.criticalFindings().stream().allMatch(GroqLlmReviewProvider::isValidFinding)
                && output.endpointImpact().stream().allMatch(GroqLlmReviewProvider::isValidEndpointImpact);
    }

    private static final class GroqStructuredReviewConverter extends BeanOutputConverter<GroqStructuredReviewOutput> {
        private GroqStructuredReviewConverter() {
            super(GroqStructuredReviewOutput.class);
        }

        @Override
        protected void postProcessSchema(JsonNode schema) {
            JsonNode properties = schema.path("properties");
            if (properties.isObject()) {
                setEnumConstraint(properties, "overallRiskLevel", "LOW", "MEDIUM", "HIGH", "CRITICAL");
                setEnumConstraint(properties, "riskAlignmentDeterministicRisk", "LOW", "MEDIUM", "HIGH", "CRITICAL");
                setEnumConstraint(properties, "riskAlignmentLlmRisk", "LOW", "MEDIUM", "HIGH", "CRITICAL");
                setEnumConstraint(properties, "riskAlignment", "ALIGNED", "DIFFERENT");
            }
            JsonNode categories = findRiskCategoriesSchema(schema);
            if (categories instanceof ObjectNode categoryArray) {
                categoryArray.put("minItems", 1);
                categoryArray.put("maxItems", MAX_RISK_CATEGORIES_PER_FILE);
            }
        }

        private static void setEnumConstraint(JsonNode properties, String propertyName, String... allowedValues) {
            JsonNode property = properties.path(propertyName);
            if (property instanceof ObjectNode propertyNode) {
                propertyNode.putArray("enum");
                com.fasterxml.jackson.databind.node.ArrayNode enumValues = (com.fasterxml.jackson.databind.node.ArrayNode) propertyNode.get("enum");
                for (String allowedValue : allowedValues) {
                    enumValues.add(allowedValue);
                }
            }
        }
    }

            static Map<String, Object> validationDiagnostics(GroqReviewOutput output) {
            Map<String, Object> details = new LinkedHashMap<>();
            List<String> failedPredicates = new ArrayList<>();
            details.put("dtoClass", output == null ? "null" : output.getClass().getSimpleName());
            addPredicate(details, failedPredicates, "outputPresent", output != null);
            if (output == null) {
                details.put("failedPredicates", failedPredicates);
                return details;
            }

            addTextPredicate(details, failedPredicates, "overallAssessment", output.overallAssessment(), 4000);
            addTextPredicate(details, failedPredicates, "changeSummary", output.changeSummary(), 4000);
            addTextPredicate(details, failedPredicates, "recommendation", output.recommendation(), 2000);

            GroqReviewOutput.OverallRisk overallRisk = output.overallRisk();
            addPredicate(details, failedPredicates, "overallRiskPresent", overallRisk != null);
            addPredicate(details, failedPredicates, "overallRiskLevelValid",
                overallRisk != null && overallRisk.level() != null);
            details.put("overallRiskScorePresent", overallRisk != null && overallRisk.score() != null);
            addPredicate(details, failedPredicates, "overallRiskScoreValid",
                overallRisk != null && validScore(overallRisk.score()));
            details.put("overallRiskConfidencePresent", overallRisk != null && overallRisk.confidence() != null);
            addPredicate(details, failedPredicates, "overallRiskConfidenceValid",
                overallRisk != null && validConfidence(overallRisk.confidence()));
            addTextPredicate(details, failedPredicates, "overallRiskReason",
                overallRisk == null ? null : overallRisk.reason(), 2000);
            details.put("reviewConfidencePresent", output.reviewConfidence() != null);
            addPredicate(details, failedPredicates, "reviewConfidenceValid", validConfidence(output.reviewConfidence()));

            GroqReviewOutput.BusinessImpact businessImpact = output.businessImpact();
            addPredicate(details, failedPredicates, "businessImpactPresent", businessImpact != null);
            addTextPredicate(details, failedPredicates, "businessImpactSummary",
                businessImpact == null ? null : businessImpact.summary(), 2000);
            addTextPredicate(details, failedPredicates, "businessImpactAffectedCapability",
                businessImpact == null ? null : businessImpact.affectedCapability(), 1000);
            details.put("businessImpactConfidencePresent",
                businessImpact != null && businessImpact.confidence() != null);
            addPredicate(details, failedPredicates, "businessImpactConfidenceValid",
                businessImpact != null && validConfidence(businessImpact.confidence()));

            List<GroqReviewOutput.FileAnalysis> files = output.files();
            details.put("filesCount", files == null ? -1 : files.size());
            addPredicate(details, failedPredicates, "filesCountValid",
                files != null && !files.isEmpty() && files.size() <= 25);
            if (files != null && files.size() <= 25) {
                for (int index = 0; index < files.size(); index++) {
                addFileDiagnostics(details, failedPredicates, index, files.get(index));
                }
            }

            List<GroqReviewOutput.CriticalFinding> findings = output.criticalFindings();
            details.put("criticalFindingsCount", findings == null ? -1 : findings.size());
            addPredicate(details, failedPredicates, "criticalFindingsCountValid",
                findings != null && findings.size() <= 50);
            if (findings != null && findings.size() <= 50) {
                for (int index = 0; index < findings.size(); index++) {
                addFindingDiagnostics(details, failedPredicates, index, findings.get(index));
                }
            }

            List<GroqReviewOutput.EndpointImpact> endpointImpacts = output.endpointImpact();
            details.put("endpointImpactCount", endpointImpacts == null ? -1 : endpointImpacts.size());
            addPredicate(details, failedPredicates, "endpointImpactCountValid",
                endpointImpacts != null && endpointImpacts.size() <= 100);
            if (endpointImpacts != null && endpointImpacts.size() <= 100) {
                for (int index = 0; index < endpointImpacts.size(); index++) {
                addEndpointDiagnostics(details, failedPredicates,
                    "endpointImpact[" + index + "]", endpointImpacts.get(index));
                }
            }

            GroqReviewOutput.RiskAlignment riskAlignment = output.riskAlignment();
            details.put("riskAlignmentPresent", riskAlignment != null);
            if (riskAlignment != null) {
                addPredicate(details, failedPredicates, "riskAlignmentDeterministicRiskValid",
                    isRiskLevelToken(riskAlignment.deterministicRisk()));
                addPredicate(details, failedPredicates, "riskAlignmentLlmRiskValid",
                    isRiskLevelToken(riskAlignment.llmRisk()));
                addPredicate(details, failedPredicates, "riskAlignmentEnumValid", riskAlignment.alignment() != null);
                addTextPredicate(details, failedPredicates, "riskAlignmentExplanation",
                    riskAlignment.explanation(), 1500);
            }
            addPredicate(details, failedPredicates, "riskAlignmentValid",
                riskAlignment == null || isValidRiskAlignment(riskAlignment));
            details.put("failedPredicates", failedPredicates);
            return details;
            }

            private static void addFileDiagnostics(
                Map<String, Object> details, List<String> failedPredicates, int index,
                GroqReviewOutput.FileAnalysis file) {
            String prefix = "files[" + index + "].";
            addPredicate(details, failedPredicates, prefix + "present", file != null);
            if (file == null) {
                return;
            }
            addTextPredicate(details, failedPredicates, prefix + "file", file.file(), 1000);
            addTextPredicate(details, failedPredicates, prefix + "changeSummary", file.changeSummary(), 2000);
            addTextPredicate(details, failedPredicates, prefix + "whatChanged", file.whatChanged(), 3000);
            addTextPredicate(details, failedPredicates, prefix + "whatItDoes", file.whatItDoes(), 3000);
            addTextPredicate(details, failedPredicates, prefix + "businessImpact", file.businessImpact(), 2000);
            addPredicate(details, failedPredicates, prefix + "riskLevelValid", file.riskLevel() != null);
            List<GroqReviewOutput.RiskCategory> categories = file.riskCategories();
            details.put(prefix + "riskCategoriesCount", categories == null ? -1 : categories.size());
            addPredicate(details, failedPredicates, prefix + "riskCategoriesValid",
                categories != null && !categories.isEmpty()
                    && categories.size() <= GroqReviewOutput.RiskCategory.values().length
                    && categories.size() <= MAX_RISK_CATEGORIES_PER_FILE
                    && categories.stream().allMatch(category -> category != null));
            addTextPredicate(details, failedPredicates, prefix + "technicalImpact", file.technicalImpact(), 3000);
            List<String> evidence = file.evidence();
            details.put(prefix + "evidenceCount", evidence == null ? -1 : evidence.size());
            addPredicate(details, failedPredicates, prefix + "evidenceValid",
                evidence != null && evidence.size() <= 25
                    && evidence.stream().allMatch(item -> !blankOrLong(item, 1000)));
            List<GroqReviewOutput.EndpointImpact> affectedEndpoints = file.affectedEndpoints();
            details.put(prefix + "affectedEndpointsCount", affectedEndpoints == null ? -1 : affectedEndpoints.size());
            addPredicate(details, failedPredicates, prefix + "affectedEndpointsValid",
                affectedEndpoints != null && affectedEndpoints.size() <= 25
                    && affectedEndpoints.stream().allMatch(GroqLlmReviewProvider::isValidEndpointImpact));
            details.put(prefix + "confidencePresent", file.confidence() != null);
            addPredicate(details, failedPredicates, prefix + "confidenceValid", validConfidence(file.confidence()));
            }

            private static void addFindingDiagnostics(
                Map<String, Object> details, List<String> failedPredicates, int index,
                GroqReviewOutput.CriticalFinding finding) {
            String prefix = "criticalFindings[" + index + "].";
            addPredicate(details, failedPredicates, prefix + "present", finding != null);
            if (finding == null) {
                return;
            }
            addPredicate(details, failedPredicates, prefix + "severityValid", finding.severity() != null);
            addPredicate(details, failedPredicates, prefix + "categoryValid", finding.category() != null);
            addTextPredicate(details, failedPredicates, prefix + "file", finding.file(), 1000);
            addTextPredicate(details, failedPredicates, prefix + "finding", finding.finding(), 2000);
            addTextPredicate(details, failedPredicates, prefix + "whyItMatters", finding.whyItMatters(), 2000);
            addTextPredicate(details, failedPredicates, prefix + "evidence", finding.evidence(), 1500);
            addTextPredicate(details, failedPredicates, prefix + "recommendedAction", finding.recommendedAction(), 2000);
            }

            private static void addEndpointDiagnostics(
                Map<String, Object> details, List<String> failedPredicates, String prefix,
                GroqReviewOutput.EndpointImpact endpointImpact) {
            addPredicate(details, failedPredicates, prefix + ".present", endpointImpact != null);
            if (endpointImpact == null) {
                return;
            }
            addTextPredicate(details, failedPredicates, prefix + ".method", endpointImpact.method(), 20);
            addTextPredicate(details, failedPredicates, prefix + ".path", endpointImpact.path(), 500);
            addTextPredicate(details, failedPredicates, prefix + ".criticality", endpointImpact.criticality(), 30);
            addTextPredicate(details, failedPredicates, prefix + ".impact", endpointImpact.impact(), 1500);
            addTextPredicate(details, failedPredicates, prefix + ".reason", endpointImpact.reason(), 1500);
            }

            private static void addTextPredicate(
                Map<String, Object> details, List<String> failedPredicates, String name,
                String value, int maxLength) {
            details.put(name + "Present", value != null && !value.isBlank());
            details.put(name + "LengthValid", value != null && value.length() <= maxLength);
            addPredicate(details, failedPredicates, name + "Valid", !blankOrLong(value, maxLength));
            }

            private static void addPredicate(
                Map<String, Object> details, List<String> failedPredicates, String name, boolean valid) {
            details.put(name, valid);
            if (!valid) {
                failedPredicates.add(name);
            }
            }

    private static boolean isValidFile(GroqReviewOutput.FileAnalysis file) {
        return file != null
                && blankOrLong(file.file(), 1000) == false
                && blankOrLong(file.changeSummary(), 2000) == false
                && blankOrLong(file.whatChanged(), 3000) == false
                && blankOrLong(file.whatItDoes(), 3000) == false
                && blankOrLong(file.businessImpact(), 2000) == false
                && file.riskLevel() != null
                && file.riskCategories() != null
                && !file.riskCategories().isEmpty()
                && file.riskCategories().size() <= GroqReviewOutput.RiskCategory.values().length
                && file.riskCategories().size() <= MAX_RISK_CATEGORIES_PER_FILE
                && file.riskCategories().stream().allMatch(category -> category != null)
                && blankOrLong(file.technicalImpact(), 3000) == false
                && file.evidence() != null && file.evidence().size() <= 25
                && file.evidence().stream().allMatch(item -> blankOrLong(item, 1000) == false)
                && file.affectedEndpoints() != null && file.affectedEndpoints().size() <= 25
                && file.affectedEndpoints().stream().allMatch(GroqLlmReviewProvider::isValidEndpointImpact)
                && validConfidence(file.confidence());
    }

    private static boolean isValidFinding(GroqReviewOutput.CriticalFinding finding) {
        return finding != null
                && finding.severity() != null
                && finding.category() != null
                && blankOrLong(finding.file(), 1000) == false
                && blankOrLong(finding.finding(), 2000) == false
                && blankOrLong(finding.whyItMatters(), 2000) == false
                && blankOrLong(finding.evidence(), 1500) == false
                && blankOrLong(finding.recommendedAction(), 2000) == false;
    }

    private static boolean isValidEndpointImpact(GroqReviewOutput.EndpointImpact impact) {
        return impact != null
                && blankOrLong(impact.method(), 20) == false
                && blankOrLong(impact.path(), 500) == false
                && blankOrLong(impact.criticality(), 30) == false
                && blankOrLong(impact.impact(), 1500) == false
                && blankOrLong(impact.reason(), 1500) == false;
    }

    private static boolean isValidRiskAlignment(GroqReviewOutput.RiskAlignment riskAlignment) {
        return riskAlignment != null
                && isRiskLevelToken(riskAlignment.deterministicRisk())
                && isRiskLevelToken(riskAlignment.llmRisk())
                && riskAlignment.alignment() != null
                && blankOrLong(riskAlignment.explanation(), 1500) == false;
    }

    private static boolean isRiskLevelToken(String value) {
        return value != null && !value.isBlank()
                && List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")
                .contains(value.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean blankOrLong(String value, int maxLength) {
        return value == null || value.isBlank() || value.length() > maxLength;
    }

    private static boolean validScore(Double value) {
        return value != null && Double.isFinite(value) && value >= 0.0 && value <= 100.0;
    }

    private static boolean validConfidence(Double value) {
        return value != null && Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private String safeExceptionMessage(
            Exception exception,
            RestClientResponseException responseException,
            String apiKey,
            ReviewRequest request) {
        if (responseException == null) {
            return "Provider call failed without an HTTP error response";
        }

        String message;
        try {
            message = objectMapper.readTree(responseException.getResponseBodyAsString())
                    .path("error").path("message").asText(responseException.getStatusText());
        } catch (Exception ignored) {
            message = responseException.getStatusText();
        }
        if (message == null || message.isBlank()) {
            message = "No provider error message provided";
        }

        message = redact(message, apiKey);
        message = redact(message, request.repository());
        message = redact(message, request.changeSummary());
        message = redact(message, request.diff());
        message = redact(message, request.affectedEndpoints());
        message = redact(message, request.pullRequestDescription());
        Matcher credentialMatcher = CREDENTIAL_HEADER.matcher(message);
        message = credentialMatcher.replaceAll("$1=[redacted]");
        message = redactTokens(message, request.changeSummary());
        message = redactTokens(message, request.diff());
        message = redactTokens(message, request.affectedEndpoints());
        message = redactTokens(message, request.pullRequestDescription());
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static RestClientResponseException findResponseException(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof RestClientResponseException responseException) {
                return responseException;
            }
            cause = cause.getCause();
        }
        return null;
    }

    private static boolean isRetryable(Exception exception, Integer status) {
        if (status != null) {
            return status == 408 || status == 429 || status >= 500;
        }
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ResourceAccessException || cause instanceof SocketTimeoutException
                    || cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static String failureCategory(Exception exception, Integer status) {
        if (status != null) {
            return switch (status) {
                case 400 -> "invalid_request";
                case 401 -> "authentication";
                case 403 -> "permission_or_account";
                case 408 -> "timeout";
                case 429 -> "rate_limit_or_quota";
                case 500 -> "provider_server_error";
                case 502 -> "bad_gateway";
                case 503 -> "service_unavailable";
                case 504 -> "gateway_timeout";
                default -> status >= 500 ? "provider_server_error" : "unknown_http_error";
            };
        }
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) return "timeout";
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof ResourceAccessException) return "network_failure";
            cause = cause.getCause();
        }
        return "unknown_provider_error";
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

    private ReviewDecision fallback(String summary) {
        return new ReviewDecision("LOW", 0.0, summary,
                List.of("Provider request or response was invalid"), providerName(), true);
    }
}
