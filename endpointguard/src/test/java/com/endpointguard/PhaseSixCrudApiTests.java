package com.endpointguard;

import com.endpointguard.github.GithubRepositoryMetadata;
import com.endpointguard.github.GithubRestApiClient;
import com.endpointguard.pullrequest.domain.PrChangedFile;
import com.endpointguard.pullrequest.domain.PullRequest;
import com.endpointguard.pullrequest.repository.PrChangedFileRepository;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.repository.repository.GithubRepositoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import jakarta.persistence.EntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(classes = PhaseSixCrudApiTests.TestGithubClientConfig.class)
class PhaseSixCrudApiTests extends RollbackIntegrationTest {

    @TestConfiguration
    static class TestGithubClientConfig {
        @Bean
        @Primary
        GithubRestApiClient githubRestApiClient() {
            GithubRestApiClient client = mock(GithubRestApiClient.class);
            when(client.validateRepository("acme/phase-six-api")).thenReturn(new GithubRepositoryMetadata(
                    "acme/phase-six-api", "acme", "phase-six-api", "Phase Six API", false, "main"));
            return client;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

        @Autowired
        private EntityManager entityManager;

        @Autowired
        private GithubRepositoryRepository githubRepositoryRepository;

        @Autowired
        private PullRequestRepository pullRequestRepository;

        @Autowired
        private PrChangedFileRepository changedFileRepository;

    @Test
    void crudMutationsAreOwnerScopedValidateRequestsAndCascadeDelete() throws Exception {
        String ownerToken = register("phase6-owner@example.com");
        String otherToken = register("phase6-other@example.com");
        long projectId = createProject(ownerToken);
        long repositoryId = createRepository(ownerToken, projectId);
        long endpointId = createEndpoint(ownerToken, repositoryId);
        long siblingEndpointId = createEndpoint(ownerToken, repositoryId, "/api/customers", "**/CustomerController.java");
        PullRequest pullRequest = pullRequestRepository.save(PullRequest.builder()
                .repository(githubRepositoryRepository.findById(repositoryId).orElseThrow())
                .githubPrNumber(91)
                .title("Phase six cascade test")
                .author("phase-six-test")
                .build());
        changedFileRepository.save(PrChangedFile.builder()
                .pullRequest(pullRequest)
                .filePath("src/main/java/OrderController.java")
                .build());

        mockMvc.perform(put("/api/repositories/" + repositoryId + "/endpoints/" + endpointId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(endpointRequest("/api/orders/{id}", "**/OrderService.java")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pathPattern").value("/api/orders/{id}"))
                .andExpect(jsonPath("$.sourcePatterns[0]").value("**/OrderService.java"));

        mockMvc.perform(put("/api/repositories/" + repositoryId + "/endpoints/" + endpointId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"GET","pathPattern":"/api/orders","criticality":"HIGH","sourcePatterns":[]}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/repositories/" + repositoryId + "/endpoints/" + endpointId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(endpointRequest("/api/forbidden", "**/Forbidden.java")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/repositories/" + repositoryId + "/endpoints/" + endpointId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/repositories/" + repositoryId + "/endpoints/" + endpointId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        entityManager.flush();
        assertThat(count("endpoints", endpointId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from endpoint_mappings where endpoint_id = ?", Integer.class, endpointId)).isZero();

        mockMvc.perform(put("/api/projects/" + projectId + "/repositories/" + repositoryId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"githubRepoFullName":"acme/phase-six-api","webhookSecretRef":"UPDATED_SECRET_REF"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookSecretRef").value("UPDATED_SECRET_REF"));

        mockMvc.perform(delete("/api/projects/" + projectId + "/repositories/" + repositoryId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/projects/" + projectId + "/repositories/" + repositoryId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        entityManager.flush();
        assertThat(count("repositories", repositoryId)).isZero();
        assertThat(count("endpoints", siblingEndpointId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from endpoint_mappings where endpoint_id = ?", Integer.class, siblingEndpointId)).isZero();
        assertThat(count("pull_requests", pullRequest.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from pr_changed_files where pull_request_id = ?", Integer.class, pullRequest.getId())).isZero();
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
                        .content("{\"name\":\"Phase Six\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private long createRepository(String token, long projectId) throws Exception {
        String response = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"githubRepoFullName":"acme/phase-six-api","webhookSecretRef":"INITIAL_SECRET_REF"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private long createEndpoint(String token, long repositoryId) throws Exception {
                return createEndpoint(token, repositoryId, "/api/orders", "**/OrderController.java");
        }

        private long createEndpoint(String token, long repositoryId, String path, String sourcePattern) throws Exception {
        String response = mockMvc.perform(post("/api/repositories/" + repositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(endpointRequest(path, sourcePattern)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode payload = objectMapper.readTree(response);
        return payload.get("id").asLong();
    }

    private static String endpointRequest(String path, String sourcePattern) {
        return """
                {"method":"GET","pathPattern":"%s","criticality":"HIGH","sourcePatterns":["%s"]}
                """.formatted(path, sourcePattern);
    }

    private int count(String table, long id) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, id);
    }
}