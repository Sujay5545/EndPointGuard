package com.endpointguard.repository.service;

import com.endpointguard.common.exception.ConflictException;
import com.endpointguard.common.exception.ResourceNotFoundException;
import com.endpointguard.endpoint.domain.Endpoint;
import com.endpointguard.endpoint.repository.EndpointMappingRepository;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.github.GithubRepositoryMetadata;
import com.endpointguard.github.GithubRestApiClient;
import com.endpointguard.metrics.repository.TrafficMetricRepository;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.pullrequest.domain.PullRequest;
import com.endpointguard.pullrequest.repository.PrChangedFileRepository;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.project.domain.Project;
import com.endpointguard.project.service.ProjectService;
import com.endpointguard.repository.domain.GithubRepository;
import com.endpointguard.repository.dto.LinkRepositoryRequest;
import com.endpointguard.repository.repository.GithubRepositoryRepository;
import com.endpointguard.risk.repository.PostMergeMonitoringRepository;
import com.endpointguard.risk.repository.RiskAssessmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GithubRepositoryService {

    private final GithubRepositoryRepository repositoryRepository;
    private final ProjectService projectService;
    private final GithubRestApiClient githubRestApiClient;
    private final EndpointRepository endpointRepository;
    private final EndpointMappingRepository endpointMappingRepository;
    private final TrafficMetricRepository trafficMetricRepository;
    private final PrAffectedEndpointRepository prAffectedEndpointRepository;
    private final PostMergeMonitoringRepository postMergeMonitoringRepository;
    private final PullRequestRepository pullRequestRepository;
    private final PrChangedFileRepository changedFileRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;

    @Transactional
    public GithubRepository link(Long projectId, String ownerEmail, LinkRepositoryRequest request) {
        Project project = projectService.getOwned(projectId, ownerEmail);
        String normalizedRepo = githubRestApiClient.validateRepository(request.githubRepoFullName()).fullName();
        if (repositoryRepository.existsByGithubRepoFullName(normalizedRepo)) {
            throw new ConflictException("Repository already linked: " + normalizedRepo);
        }
        return repositoryRepository.save(GithubRepository.builder()
                .project(project)
                .githubRepoFullName(normalizedRepo)
                .webhookSecretRef(request.webhookSecretRef())
                .build());
    }

    @Transactional(readOnly = true)
    public List<GithubRepository> list(Long projectId, String ownerEmail) {
        projectService.getOwned(projectId, ownerEmail);
        return repositoryRepository.findByProjectIdOrderByInstalledAtDesc(projectId);
    }

    @Transactional
    public GithubRepository update(Long projectId, Long repositoryId, String ownerEmail, LinkRepositoryRequest request) {
        projectService.getOwned(projectId, ownerEmail);
        GithubRepository repository = repositoryRepository.findById(repositoryId)
                .filter(item -> item.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));
        String normalizedRepo = githubRestApiClient.validateRepository(request.githubRepoFullName()).fullName();
        repositoryRepository.findByGithubRepoFullNameIgnoreCase(normalizedRepo)
                .filter(existing -> !existing.getId().equals(repositoryId))
                .ifPresent(existing -> { throw new ConflictException("Repository already linked: " + normalizedRepo); });
        repository.setGithubRepoFullName(normalizedRepo);
        repository.setWebhookSecretRef(request.webhookSecretRef());
        return repositoryRepository.save(repository);
    }

    @Transactional
    public void delete(Long projectId, Long repositoryId, String ownerEmail) {
        projectService.getOwned(projectId, ownerEmail);
        GithubRepository repository = repositoryRepository.findById(repositoryId)
                .filter(item -> item.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));

        for (Endpoint endpoint : endpointRepository.findByRepositoryId(repositoryId)) {
            trafficMetricRepository.deleteByEndpointId(endpoint.getId());
            endpointMappingRepository.deleteByEndpointId(endpoint.getId());
            prAffectedEndpointRepository.deleteByEndpoint_Id(endpoint.getId());
            postMergeMonitoringRepository.deleteByEndpointId(endpoint.getId());
        }
        List<PullRequest> pullRequests = pullRequestRepository.findByRepository_Id(repositoryId);
        for (PullRequest pullRequest : pullRequests) {
            Long pullRequestId = pullRequest.getId();
            changedFileRepository.deleteByPullRequest_Id(pullRequestId);
            prAffectedEndpointRepository.deleteByPullRequest_Id(pullRequestId);
            postMergeMonitoringRepository.deleteByPullRequestId(pullRequestId);
            riskAssessmentRepository.deleteByPullRequestId(pullRequestId);
        }
        pullRequestRepository.deleteAll(pullRequests);
        endpointRepository.deleteAll(endpointRepository.findByRepositoryId(repositoryId));
        repositoryRepository.delete(repository);
    }
}