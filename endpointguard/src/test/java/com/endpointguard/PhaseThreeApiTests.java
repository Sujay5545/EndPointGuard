package com.endpointguard;

import com.endpointguard.github.GithubRepositoryMetadata;
import com.endpointguard.github.GithubRestApiClient;
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
@ContextConfiguration(classes = PhaseThreeApiTests.TestGithubClientConfig.class)
class PhaseThreeApiTests extends RollbackIntegrationTest {

    @TestConfiguration
    static class TestGithubClientConfig {
        @Bean
        @Primary
        GithubRestApiClient githubRestApiClient() {
            GithubRestApiClient client = mock(GithubRestApiClient.class);
            when(client.validateRepository("acme/catalog-api")).thenReturn(new GithubRepositoryMetadata(
                    "acme/catalog-api",
                    "acme",
                    "catalog-api",
                    "Catalog API",
                    false,
                    "main"
            ));
            when(client.validateRepository("acme/catalog-worker")).thenReturn(new GithubRepositoryMetadata(
                    "acme/catalog-worker",
                    "acme",
                    "catalog-worker",
                    "Catalog worker",
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
    void registeredSourcePatternResolvesChangedFileToEndpoint() throws Exception {
        String tokenResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"phase3@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(tokenResponse).get("token").asText();

        String projectResponse = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Catalog API\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long projectId = objectMapper.readTree(projectResponse).get("id").asLong();

        String repositoryResponse = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"acme/catalog-api\",\"webhookSecretRef\":\"CATALOG_WEBHOOK_SECRET\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long repositoryId = objectMapper.readTree(repositoryResponse).get("id").asLong();

        mockMvc.perform(post("/api/repositories/" + repositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "get",
                                  "pathPattern": "/api/products",
                                  "criticality": "HIGH",
                                  "sourcePatterns": ["**/ProductController.java"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.sourcePatterns[0]").value("**/ProductController.java"));

        String secondRepositoryResponse = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"acme/catalog-worker\",\"webhookSecretRef\":\"WORKER_SECRET\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long secondRepositoryId = objectMapper.readTree(secondRepositoryResponse).get("id").asLong();

        mockMvc.perform(get("/api/repositories/" + secondRepositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/api/repositories/" + repositoryId + "/endpoints/resolve")
                        .param("filePath", "src/main/java/com/acme/ProductController.java")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pathPattern").value("/api/products"));

        mockMvc.perform(get("/api/repositories/" + repositoryId + "/endpoints/resolve")
                        .param("filePath", "src/main/java/com/acme/OrderController.java")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}