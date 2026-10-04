package com.endpointguard;

import com.endpointguard.github.GithubApiException;
import com.endpointguard.github.GithubRepositoryMetadata;
import com.endpointguard.github.GithubRestApiClient;
import com.endpointguard.endpoint.repository.EndpointMappingRepository;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.metrics.repository.TrafficMetricRepository;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.pullrequest.repository.PrChangedFileRepository;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.project.domain.Project;
import com.endpointguard.project.service.ProjectService;
import com.endpointguard.repository.domain.GithubRepository;
import com.endpointguard.repository.dto.LinkRepositoryRequest;
import com.endpointguard.repository.repository.GithubRepositoryRepository;
import com.endpointguard.repository.service.GithubRepositoryService;
import com.endpointguard.risk.repository.PostMergeMonitoringRepository;
import com.endpointguard.risk.repository.RiskAssessmentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GithubRepositoryServiceTests {

    @Test
    void savesCanonicalRepositoryMetadataReturnedByGithub() {
        GithubRepositoryRepository repositoryRepository = mock(GithubRepositoryRepository.class);
        ProjectService projectService = mock(ProjectService.class);
        GithubRestApiClient githubRestApiClient = mock(GithubRestApiClient.class);
        GithubRepositoryService service = service(repositoryRepository, projectService, githubRestApiClient);

        Project project = Project.builder().id(7L).name("Payments API").build();
        when(projectService.getOwned(7L, "owner@example.com")).thenReturn(project);
        when(repositoryRepository.existsByGithubRepoFullName("acme/payments-api")).thenReturn(false);
        when(githubRestApiClient.validateRepository("acme/payments-api")).thenReturn(new GithubRepositoryMetadata("acme/payments-api", "acme", "payments-api", "The repo", false, "main"));
        when(repositoryRepository.save(any(GithubRepository.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GithubRepository saved = service.link(7L, "owner@example.com", new LinkRepositoryRequest("acme/payments-api", "GITHUB_WEBHOOK_SECRET"));

        assertThat(saved.getGithubRepoFullName()).isEqualTo("acme/payments-api");
        ArgumentCaptor<GithubRepository> repositoryCaptor = ArgumentCaptor.forClass(GithubRepository.class);
        verify(repositoryRepository).save(repositoryCaptor.capture());
        assertThat(repositoryCaptor.getValue().getGithubRepoFullName()).isEqualTo("acme/payments-api");
    }

    @Test
    void doesNotPersistRepositoryWhenGithubValidationFails() {
        GithubRepositoryRepository repositoryRepository = mock(GithubRepositoryRepository.class);
        ProjectService projectService = mock(ProjectService.class);
        GithubRestApiClient githubRestApiClient = mock(GithubRestApiClient.class);
        GithubRepositoryService service = service(repositoryRepository, projectService, githubRestApiClient);

        Project project = Project.builder().id(8L).name("Payments API").build();
        when(projectService.getOwned(8L, "owner@example.com")).thenReturn(project);
        when(githubRestApiClient.validateRepository("acme/missing-repo")).thenThrow(new GithubApiException("Repository not found"));

        assertThatThrownBy(() -> service.link(8L, "owner@example.com", new LinkRepositoryRequest("acme/missing-repo", "GITHUB_WEBHOOK_SECRET")))
            .isInstanceOf(GithubApiException.class)
            .hasMessageContaining("Repository not found");

        verify(repositoryRepository, never()).save(any(GithubRepository.class));
    }

    private static GithubRepositoryService service(
            GithubRepositoryRepository repositoryRepository,
            ProjectService projectService,
            GithubRestApiClient githubRestApiClient) {
        return new GithubRepositoryService(
                repositoryRepository,
                projectService,
                githubRestApiClient,
                mock(EndpointRepository.class),
                mock(EndpointMappingRepository.class),
                mock(TrafficMetricRepository.class),
                mock(PrAffectedEndpointRepository.class),
                mock(PostMergeMonitoringRepository.class),
                mock(PullRequestRepository.class),
                mock(PrChangedFileRepository.class),
                mock(RiskAssessmentRepository.class));
    }

}
