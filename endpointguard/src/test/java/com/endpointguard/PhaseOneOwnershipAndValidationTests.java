package com.endpointguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhaseOneOwnershipAndValidationTests extends RollbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void anotherUserCannotReadMetricsOrEvaluateRiskForEndpoint() throws Exception {
        String ownerToken = register("phase1-owner@example.com");
        String otherToken = register("phase1-other@example.com");
        long endpointId = createEndpoint(ownerToken, "phase1/owner-api");

        mockMvc.perform(get("/api/endpoints/" + endpointId + "/metrics")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/endpoints/" + endpointId + "/metrics")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/endpoints/" + endpointId + "/risk/evaluate")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRiskRequest(1L)))
                .andExpect(status().isNotFound());
    }

    @Test
        void riskEvaluationRequiresAnOwnedPullRequest() throws Exception {
        String ownerToken = register("phase1-validation@example.com");
        long endpointId = createEndpoint(ownerToken, "phase1/validation-api");

        mockMvc.perform(post("/api/endpoints/" + endpointId + "/risk/evaluate")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pullRequestId": 1,
                                  "diffSize": 0,
                                  "incidentCount": 0,
                                  "requestCount": 0,
                                  "rateLimitUtilization": 101.0,
                                  "recentErrorRate": 0.0,
                                  "baselineErrorRate": 0.0,
                                  "recentLatencyMs": 0.0,
                                  "baselineLatencyMs": 0.0
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void endpointRegistrationRejectsUnsupportedMethodsAndPathsWithoutLeadingSlash() throws Exception {
        String ownerToken = register("phase1-endpoint-validation@example.com");
        long repositoryId = createRepository(ownerToken, "phase1/endpoint-validation-api");

        mockMvc.perform(post("/api/repositories/" + repositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "TRACE",
                                  "pathPattern": "/api/orders",
                                  "criticality": "MEDIUM",
                                  "sourcePatterns": ["**/OrderController.java"]
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/repositories/" + repositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "GET",
                                  "pathPattern": "/api/order items",
                                  "criticality": "MEDIUM",
                                  "sourcePatterns": ["**/OrderController.java"]
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/repositories/" + repositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "GET",
                                  "pathPattern": "api/orders",
                                  "criticality": "MEDIUM",
                                  "sourcePatterns": ["**/OrderController.java"]
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private long createEndpoint(String token, String repositoryName) throws Exception {
        long repositoryId = createRepository(token, repositoryName);

        String endpointResponse = mockMvc.perform(post("/api/repositories/" + repositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "GET",
                                  "pathPattern": "/api/orders",
                                  "criticality": "HIGH",
                                  "sourcePatterns": ["**/OrderController.java"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(endpointResponse).get("id").asLong();
    }

    private long createRepository(String token, String repositoryName) throws Exception {
        String projectResponse = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Phase 1 test\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long projectId = objectMapper.readTree(projectResponse).get("id").asLong();

        String repositoryResponse = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"" + repositoryName + "\",\"webhookSecretRef\":\"PHASE1_TEST\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(repositoryResponse).get("id").asLong();
    }

    private static String validRiskRequest(long pullRequestId) {
        return """
                {
                  "pullRequestId": %d,
                  "diffSize": 0,
                  "incidentCount": 0,
                  "requestCount": 0,
                  "rateLimitUtilization": 0.0,
                  "recentErrorRate": 0.0,
                  "baselineErrorRate": 0.0,
                  "recentLatencyMs": 0.0,
                  "baselineLatencyMs": 0.0
                }
                """.formatted(pullRequestId);
    }
}
