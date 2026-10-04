package com.endpointguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.endpointguard.common.config.AppProperties;
import com.endpointguard.common.time.UtcDateTime;
import com.endpointguard.endpoint.domain.Endpoint;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.metrics.domain.TrafficMetric;
import com.endpointguard.metrics.dto.DemoApiStatsResponse;
import com.endpointguard.metrics.repository.TrafficMetricRepository;
import com.endpointguard.metrics.service.MetricsPollerService;
import com.endpointguard.project.repository.ProjectRepository;
import com.endpointguard.repository.repository.GithubRepositoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhaseFourMetricsApiTests extends RollbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private GithubRepositoryRepository repositoryRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private TrafficMetricRepository trafficMetricRepository;

    @Autowired
    private MetricsPollerService metricsPollerService;

    @Autowired
    private AppProperties appProperties;

    @Test
    void metricsStatsArePersistedPerEndpointAndMalformedEntriesAreIgnored() throws Exception {
        String tokenResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"phase4@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = objectMapper.readTree(tokenResponse).get("token").asText();

        String projectResponse = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Metrics API\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long projectId = objectMapper.readTree(projectResponse).get("id").asLong();

        String repositoryResponse = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"acme/metrics-api\",\"webhookSecretRef\":\"METRICS_SECRET\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long repositoryId = objectMapper.readTree(repositoryResponse).get("id").asLong();

        String endpointPayload = """
                {
                  "method": "GET",
                  "pathPattern": "/api/products",
                  "criticality": "HIGH",
                  "sourcePatterns": ["**/ProductController.java"]
                }
                """;

        String endpointResponse = mockMvc.perform(post("/api/repositories/" + repositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(endpointPayload))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long endpointId = objectMapper.readTree(endpointResponse).get("id").asLong();

        String secondRepositoryResponse = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"acme/metrics-worker\",\"webhookSecretRef\":\"METRICS_WORKER_SECRET\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long secondRepositoryId = objectMapper.readTree(secondRepositoryResponse).get("id").asLong();

        String secondEndpointResponse = mockMvc.perform(post("/api/repositories/" + secondRepositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(endpointPayload))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long secondEndpointId = objectMapper.readTree(secondEndpointResponse).get("id").asLong();

        Endpoint endpoint = endpointRepository.findById(endpointId).orElseThrow();
        Endpoint secondEndpoint = endpointRepository.findById(secondEndpointId).orElseThrow();

        DemoApiStatsResponse stats = new DemoApiStatsResponse(
                "2026-09-26T00:00:00Z",
                60,
                java.util.List.of(
                        new DemoApiStatsResponse.EndpointStat("GET:/api/products", 12L, 1L, 0L, 125.5, 5.0),
                        new DemoApiStatsResponse.EndpointStat("POST:/api/orders", 20L, 2L, 1L, 250.0, 10.0),
                        new DemoApiStatsResponse.EndpointStat("", 5L, 0L, 0L, 1.0, 1.0),
                        new DemoApiStatsResponse.EndpointStat(null, 5L, 0L, 0L, 1.0, 1.0)
                )
        );

        metricsPollerService.processStats(stats, UtcDateTime.now().withSecond(0).withNano(0));

        java.util.List<TrafficMetric> storedMetrics = trafficMetricRepository.findByEndpointIdAndBucketStartBetweenOrderByBucketStartAsc(
                endpoint.getId(), UtcDateTime.now().minusMinutes(5), UtcDateTime.now().plusMinutes(5));

        assertThat(storedMetrics).hasSize(1);
        assertThat(storedMetrics.get(0).getRequestCount()).isEqualTo(12L);
        assertThat(storedMetrics.get(0).getAvgLatencyMs()).isEqualTo(125.5);

        java.util.List<TrafficMetric> secondEndpointMetrics = trafficMetricRepository.findByEndpointIdAndBucketStartBetweenOrderByBucketStartAsc(
                secondEndpoint.getId(), UtcDateTime.now().minusMinutes(5), UtcDateTime.now().plusMinutes(5));
        assertThat(secondEndpointMetrics).hasSize(1);
        assertThat(secondEndpointMetrics.get(0).getRequestCount()).isEqualTo(12L);

        mockMvc.perform(get("/api/endpoints/" + endpoint.getId() + "/metrics")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestCount").value(12));
    }
}
