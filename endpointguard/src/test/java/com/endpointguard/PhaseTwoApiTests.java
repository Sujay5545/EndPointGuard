package com.endpointguard;

import com.endpointguard.github.GithubRepositoryMetadata;
import com.endpointguard.github.GithubRestApiClient;
import com.fasterxml.jackson.databind.JsonNode;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(classes = PhaseTwoApiTests.TestGithubClientConfig.class)
class PhaseTwoApiTests extends RollbackIntegrationTest {

    @TestConfiguration
    static class TestGithubClientConfig {
        @Bean
        @Primary
        GithubRestApiClient githubRestApiClient() {
            GithubRestApiClient client = mock(GithubRestApiClient.class);
            when(client.validateRepository("acme/payments-api")).thenReturn(new GithubRepositoryMetadata(
                    "acme/payments-api",
                    "acme",
                    "payments-api",
                    "Payments API",
                    false,
                    "main"
            ));
            when(client.validateRepository("https://github.com/acme/payments-api")).thenReturn(new GithubRepositoryMetadata(
                    "acme/payments-api",
                    "acme",
                    "payments-api",
                    "Payments API",
                    false,
                    "main"
            ));
            return client;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void userCanRegisterCreateProjectAndLinkRepository() throws Exception {
        String tokenResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "phase2@example.com",
                                  "password": "password123",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = objectMapper.readTree(tokenResponse).get("token").asText();

        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Payments API\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Payments API"))
                .andExpect(jsonPath("$.ownerEmail").value("phase2@example.com"));

        String projectsResponse = mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Payments API"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode projects = objectMapper.readTree(projectsResponse);
        long projectId = projects.get(0).get("id").asLong();

        mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "githubRepoFullName": "acme/payments-api",
                                  "webhookSecretRef": "GITHUB_WEBHOOK_SECRET"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.githubRepoFullName").value("acme/payments-api"));

        mockMvc.perform(get("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].githubRepoFullName").value("acme/payments-api"));
    }
}