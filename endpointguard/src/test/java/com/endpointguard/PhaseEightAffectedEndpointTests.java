package com.endpointguard;

import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhaseEightAffectedEndpointTests extends RollbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PrAffectedEndpointRepository prAffectedEndpointRepository;

    @Test
    void githubWebhookMapsChangedFilesToAffectedEndpoints() throws Exception {
        String tokenResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"phase8@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(tokenResponse).get("token").asText();

        String projectResponse = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Affected Endpoints\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long projectId = objectMapper.readTree(projectResponse).get("id").asLong();

        String repositoryResponse = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"acme/affected-endpoints\",\"webhookSecretRef\":\"AFFECTED_SECRET\"}"))
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
        objectMapper.readTree(endpointResponse).get("id").asLong();

        String payload = """
                {
                  "action": "opened",
                  "repository": {
                    "full_name": "acme/affected-endpoints"
                  },
                  "pull_request": {
                    "number": 24,
                    "title": "Order endpoint refactor",
                    "user": { "login": "bob" },
                    "state": "open",
                    "created_at": "2026-09-26T11:00:00Z",
                    "head": { "sha": "def456" }
                  },
                  "files": [
                    { "filename": "src/main/java/com/acme/OrderController.java", "additions": 50, "deletions": 4 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "phase8-delivery-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        assertThat(prAffectedEndpointRepository.findAll()).isNotEmpty();
        assertThat(prAffectedEndpointRepository.findAll()).anyMatch(link ->
                link.getPullRequest().getGithubPrNumber().equals(24));

                                String updatedPayload = """
                                                                {
                                                                        "action": "synchronize",
                                                                        "repository": { "full_name": "acme/affected-endpoints" },
                                                                        "pull_request": {
                                                                                "number": 24,
                                                                                "title": "Order endpoint refactor",
                                                                                "user": { "login": "bob" },
                                                                                "state": "open",
                                                                                "created_at": "2026-09-26T11:00:00Z",
                                                                                "head": { "sha": "ghi789" }
                                                                        },
                                                                        "files": [
                                                                                { "filename": "README.md", "additions": 1, "deletions": 0 }
                                                                        ]
                                                                }
                                                                """;

                                mockMvc.perform(post("/api/webhooks/github")
                                                                                                .header("X-GitHub-Event", "pull_request")
                                                                                                .header("X-GitHub-Delivery", "phase8-delivery-002")
                                                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                                                .content(updatedPayload))
                                                                .andExpect(status().isAccepted());

                                assertThat(prAffectedEndpointRepository.findAll()).isEmpty();
    }
}
