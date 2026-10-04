package com.endpointguard;

import com.endpointguard.github.GithubRepositoryMetadata;
import com.endpointguard.github.GithubRestApiClient;
import com.endpointguard.github.GithubChangedFile;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.metrics.domain.TrafficMetric;
import com.endpointguard.metrics.repository.TrafficMetricRepository;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.pullrequest.repository.PrChangedFileRepository;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.repository.domain.GithubRepository;
import com.endpointguard.repository.repository.GithubRepositoryRepository;
import com.endpointguard.risk.domain.PostMergeMonitoring;
import com.endpointguard.risk.repository.PostMergeMonitoringRepository;
import com.endpointguard.risk.repository.RiskAssessmentRepository;
import com.endpointguard.risk.service.PostMergeMonitoringService;
import com.endpointguard.pullrequest.repository.WebhookEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(classes = PhaseSevenApiTests.TestGithubClientConfig.class)
class PhaseSevenApiTests extends RollbackIntegrationTest {

    @TestConfiguration
    static class TestGithubClientConfig {
        @Bean
        @Primary
        GithubRestApiClient githubRestApiClient() {
            GithubRestApiClient client = mock(GithubRestApiClient.class);
            when(client.validateRepository("acme/phase-seven-api")).thenReturn(new GithubRepositoryMetadata(
                    "acme/phase-seven-api", "acme", "phase-seven-api", "Phase Seven API", false, "main"));
            when(client.isConfigured()).thenReturn(true);
            when(client.getPullRequestFiles("acme/phase-seven-api", 74)).thenReturn(List.of(
                    new GithubChangedFile("src/main/java/OrderController.java", 8, 2, "@@ -1 +1,8 @@\n+order handler")));
            return client;
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PostMergeMonitoringRepository monitoringRepository;
    @Autowired private TrafficMetricRepository metricRepository;
        @Autowired private EndpointRepository endpointRepository;
        @Autowired private GithubRepositoryRepository repositoryRepository;
        @Autowired private PullRequestRepository pullRequestRepository;
        @Autowired private PrChangedFileRepository changedFileRepository;
        @Autowired private PrAffectedEndpointRepository affectedEndpointRepository;
        @Autowired private RiskAssessmentRepository riskAssessmentRepository;
        @Autowired private WebhookEventRepository webhookEventRepository;
        @Autowired private GithubRestApiClient githubClient;
    @Autowired private PostMergeMonitoringService monitoringService;

    @Test
    void mergeStartsOneWindowAfterRiskEvaluationAndWindowProducesAuditedVerdict() throws Exception {
        String token = register("phase7-monitor@example.com");
        long projectId = createProject(token);
        long repositoryId = createRepository(token, projectId);
        long endpointId = createEndpoint(token, repositoryId);
        String openedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(6).withNano(0).toString();

        sendWebhook("phase7-open", pullRequestPayload("open", null, openedAt));
        GithubRepository repository = repositoryRepository.findById(repositoryId).orElseThrow();
        long pullRequestId = pullRequestRepository.findByRepositoryAndGithubPrNumber(repository, 73)
                .orElseThrow().getId();
        mockMvc.perform(post("/api/endpoints/" + endpointId + "/risk/evaluate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pullRequestId":%d,"diffSize":0,"incidentCount":0,"requestCount":0,
                                 "rateLimitUtilization":0,"recentErrorRate":0,"baselineErrorRate":0,
                                 "recentLatencyMs":0,"baselineLatencyMs":0}
                                """.formatted(pullRequestId)))
                .andExpect(status().isOk());
        assertThat(monitoringRepository.findAll()).isEmpty();

        String mergedAtText = OffsetDateTime.now(ZoneOffset.UTC).minusHours(5).withNano(0).toString();
        String mergedPayload = pullRequestPayload("closed", mergedAtText, openedAt);
        sendWebhook("phase7-merge", mergedPayload);
        sendWebhook("phase7-merge-repeat", mergedPayload);
        PostMergeMonitoring record = monitoringRepository.findAll().get(0);
        assertThat(record.getVerdict()).isEqualTo("PENDING");
        assertThat(record.getEvaluatedAt()).isNull();
        assertThat(monitoringRepository.findAll()).hasSize(1);

        LocalDateTime mergedAt = OffsetDateTime.parse(mergedAtText).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var endpoint = endpointRepository.findById(endpointId).orElseThrow();
        metricRepository.saveAll(List.of(
                metric(endpoint, mergedAt.minusMinutes(30), 100L, 10L, 200.0),
                metric(endpoint, mergedAt.plusMinutes(30), 100L, 1L, 150.0)));
        monitoringService.evaluateDueWindows(mergedAt.plusHours(2).plusMinutes(1));
        PostMergeMonitoring resolved = monitoringRepository.findById(record.getId()).orElseThrow();
        assertThat(resolved.getVerdict()).isEqualTo("IMPROVED");
        assertThat(resolved.getBaselineRequestCount()).isEqualTo(100L);
        assertThat(resolved.getObservedRequestCount()).isEqualTo(100L);
        mockMvc.perform(get("/api/projects/" + projectId + "/monitoring")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].verdict").value("IMPROVED"));
        String auditResponse = mockMvc.perform(get("/api/audit-logs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(auditResponse).contains("MONITORING_VERDICT");
        String otherToken = register("phase7-monitor-other@example.com");
        mockMvc.perform(get("/api/projects/" + projectId + "/monitoring")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void passwordChangeValidatesCurrentPasswordAndAuditNeverContainsCredentials() throws Exception {
        mockMvc.perform(get("/api/auth/profile"))
                .andExpect(status().isForbidden());
        String token = register("phase7-profile@example.com");
        mockMvc.perform(get("/api/auth/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("phase7-profile@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-password\",\"newPassword\":\"new-secure-password\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password123\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "currentPassword", "password123", "newPassword", "\u00e9".repeat(37)))))
                .andExpect(status().isBadRequest());

        String changedResponse = mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password123\",\"newPassword\":\"new-secure-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String refreshedToken = objectMapper.readTree(changedResponse).get("token").asText();
        mockMvc.perform(get("/api/auth/profile").header("Authorization", "Bearer " + refreshedToken))
                .andExpect(status().isOk());

        String auditResponse = mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + refreshedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("PASSWORD_CHANGED"))
                .andExpect(jsonPath("$[0].details.changed").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(auditResponse).doesNotContain("password123", "new-secure-password", "currentPassword", "newPassword");

        String otherToken = register("phase7-audit-other@example.com");
        mockMvc.perform(get("/api/audit-logs").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void standardWebhookFetchesPatchesMapsFilesAndAutomaticallyPersistsRisk() throws Exception {
        String token = register("phase7-auto-risk@example.com");
        long projectId = createProject(token);
        long repositoryId = createRepository(token, projectId);
        long endpointId = createEndpoint(token, repositoryId);
        String createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).withNano(0).toString();
        String payload = """
                {
                  "action":"opened",
                  "repository":{"full_name":"acme/phase-seven-api"},
                  "pull_request":{"number":74,"title":"Automatic risk","state":"open",
                    "created_at":"%s","user":{"login":"phase-seven"},"head":{"sha":"def456"}}
                }
                """.formatted(createdAt);

        sendWebhook("phase7-standard-payload", payload);

        var pullRequest = pullRequestRepository.findByRepositoryAndGithubPrNumber(
                repositoryRepository.findById(repositoryId).orElseThrow(), 74).orElseThrow();
        assertThat(changedFileRepositoryFor(pullRequest.getId()))
                .anySatisfy(file -> {
                    assertThat(file.getPatch()).contains("order handler");
                    assertThat(file.getFilePath()).endsWith("OrderController.java");
                });
        assertThat(riskAssessmentRepository.findByPullRequestIdOrderByComputedAtDesc(pullRequest.getId()))
                .anySatisfy(assessment -> {
                    assertThat(assessment.getEvaluationStatus()).isEqualTo("INSUFFICIENT_DATA");
                    assertThat(assessment.getDataQualityNotes().toString()).contains("RECENT_SAMPLE_COUNT_BELOW_MINIMUM");
                });
        assertThat(webhookEventRepository.findByGithubDeliveryId("phase7-standard-payload"))
                .hasValueSatisfying(event -> assertThat(event.getProcessingStatus()).isEqualTo("PROCESSED"));
        assertThat(affectedEndpointRepository.existsByPullRequest_IdAndEndpoint_Id(pullRequest.getId(), endpointId)).isTrue();
        org.mockito.Mockito.verify(githubClient).getPullRequestFiles("acme/phase-seven-api", 74);
    }

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private long createProject(String token) throws Exception {
        String response = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Phase Seven\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private long createRepository(String token, long projectId) throws Exception {
        String response = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"acme/phase-seven-api\",\"webhookSecretRef\":\"PHASE7_SECRET\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private long createEndpoint(String token, long repositoryId) throws Exception {
        String response = mockMvc.perform(post("/api/repositories/" + repositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"POST\",\"pathPattern\":\"/api/orders\",\"criticality\":\"HIGH\",\"sourcePatterns\":[\"**/OrderController.java\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private void sendWebhook(String deliveryId, String payload) throws Exception {
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", deliveryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());
    }

        private List<com.endpointguard.pullrequest.domain.PrChangedFile> changedFileRepositoryFor(Long pullRequestId) {
                return changedFileRepository.findByPullRequestOrderByFilePathAsc(
                                pullRequestRepository.findById(pullRequestId).orElseThrow());
        }

        private static TrafficMetric metric(com.endpointguard.endpoint.domain.Endpoint endpoint,
                        LocalDateTime bucket, long requests, long serverErrors, double latency) {
                return TrafficMetric.builder()
                                .endpoint(endpoint)
                                .bucketStart(bucket)
                                .requestCount(requests)
                                .error4xxCount(0L)
                                .error5xxCount(serverErrors)
                                .avgLatencyMs(latency)
                                .rateLimitUtilizationPct(0.0)
                                .build();
        }

    private static String pullRequestPayload(String state, String mergedAt, String openedAt) {
        String mergedAtJson = mergedAt == null ? "null" : "\"" + mergedAt + "\"";
        return """
                {
                  "action":"%s",
                  "repository":{"full_name":"acme/phase-seven-api"},
                  "pull_request":{
                    "number":73,"title":"Merge-aware monitoring","state":"%s",
                    "created_at":"%s","merged_at":%s,
                    "user":{"login":"phase-seven"},"head":{"sha":"abc123"}
                  },
                  "files":[{"filename":"src/main/java/OrderController.java","additions":4,"deletions":1}]
                }
                """.formatted(state, state, openedAt, mergedAtJson);
    }
}