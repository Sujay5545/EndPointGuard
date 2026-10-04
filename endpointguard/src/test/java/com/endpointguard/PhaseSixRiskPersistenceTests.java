package com.endpointguard;

import com.endpointguard.risk.domain.PostMergeMonitoring;
import com.endpointguard.risk.domain.RiskAssessment;
import com.endpointguard.risk.repository.PostMergeMonitoringRepository;
import com.endpointguard.risk.repository.RiskAssessmentRepository;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.github.GithubRepositoryMetadata;
import com.endpointguard.github.GithubRestApiClient;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.repository.repository.GithubRepositoryRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(classes = PhaseSixRiskPersistenceTests.TestGithubClientConfig.class)
class PhaseSixRiskPersistenceTests extends RollbackIntegrationTest {

        @TestConfiguration
        static class TestGithubClientConfig {
                @Bean
                @Primary
                GithubRestApiClient githubRestApiClient() {
                        GithubRestApiClient client = org.mockito.Mockito.mock(GithubRestApiClient.class);
                        org.mockito.Mockito.when(client.validateRepository("acme/risk-persistence"))
                                        .thenReturn(new GithubRepositoryMetadata("acme/risk-persistence", "acme",
                                                        "risk-persistence", "Risk persistence", false, "main"));
                        return client;
                }
        }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RiskAssessmentRepository riskAssessmentRepository;

    @Autowired
    private PostMergeMonitoringRepository postMergeMonitoringRepository;

        @Autowired
        private GithubRepositoryRepository githubRepositoryRepository;

        @Autowired
        private PullRequestRepository pullRequestRepository;

        @Autowired
        private EndpointRepository endpointRepository;

        @Autowired
        private PrAffectedEndpointRepository prAffectedEndpointRepository;

    @Test
        void riskEvaluationPersistsAssessmentWithoutStartingMonitoring() throws Exception {
        String tokenResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"phase6@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(tokenResponse).get("token").asText();

        String projectResponse = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Risk Persistence\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long projectId = objectMapper.readTree(projectResponse).get("id").asLong();

        String repositoryResponse = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"acme/risk-persistence\",\"webhookSecretRef\":\"RISK_SECRET\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long repositoryId = objectMapper.readTree(repositoryResponse).get("id").asLong();

        String endpointResponse = mockMvc.perform(post("/api/repositories/" + repositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "POST",
                                  "pathPattern": "/api/orders",
                                  "criticality": "HIGH",
                                  "sourcePatterns": ["**/OrderController.java"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long endpointId = objectMapper.readTree(endpointResponse).get("id").asLong();

        long pullRequestId = PhaseOneTestData.createLinkedPullRequest(99, repositoryId, endpointId,
                githubRepositoryRepository, pullRequestRepository, endpointRepository, prAffectedEndpointRepository);

        mockMvc.perform(post("/api/endpoints/" + endpointId + "/risk/evaluate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pullRequestId": %d,
                                  "diffSize": 1500,
                                  "incidentCount": 9,
                                  "requestCount": 2000,
                                  "rateLimitUtilization": 80.0,
                                  "recentErrorRate": 0.22,
                                  "baselineErrorRate": 0.05,
                                  "recentLatencyMs": 600.0,
                                  "baselineLatencyMs": 220.0
                                }
                                """.formatted(pullRequestId)))
                .andExpect(status().isOk());

        assertThat(riskAssessmentRepository.findAll()).isNotEmpty();
        assertThat(riskAssessmentRepository.findAll()).anyMatch(assessment ->
                assessment.getPullRequestId().equals(pullRequestId)
                        && assessment.getEvaluationStatus().equals("INSUFFICIENT_DATA")
                        && assessment.getTier().equals("INSUFFICIENT_DATA")
                        && assessment.getScore() == 0.0);
        assertThat(postMergeMonitoringRepository.findAll())
                .noneMatch(record -> record.getPullRequestId().equals(pullRequestId));

        PostMergeMonitoring monitoring = PostMergeMonitoring.builder()
                .pullRequestId(pullRequestId)
                .endpointId(endpointId)
                .windowStart(LocalDateTime.now().minusHours(2))
                .windowEnd(LocalDateTime.now())
                .baselineErrorRate(0.04)
                .observedErrorRate(0.18)
                .baselineLatencyMs(220.0)
                .observedLatencyMs(460.0)
                .baselineRequestCount(1200L)
                .observedRequestCount(2000L)
                .verdict("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        postMergeMonitoringRepository.save(monitoring);

        assertThat(postMergeMonitoringRepository.findAll()).anyMatch(record ->
                record.getPullRequestId().equals(pullRequestId) && record.getEndpointId().equals(endpointId));
    }
}
