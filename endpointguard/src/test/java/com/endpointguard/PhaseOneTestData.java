package com.endpointguard;

import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.praffected.domain.PrAffectedEndpoint;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.pullrequest.domain.PullRequest;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.repository.repository.GithubRepositoryRepository;

final class PhaseOneTestData {

    private PhaseOneTestData() {}

    static Long createLinkedPullRequest(
            Integer githubPrNumber,
            Long repositoryId,
            Long endpointId,
            GithubRepositoryRepository githubRepositoryRepository,
            PullRequestRepository pullRequestRepository,
            EndpointRepository endpointRepository,
            PrAffectedEndpointRepository affectedEndpointRepository) {
        PullRequest pullRequest = pullRequestRepository.save(PullRequest.builder()
                .repository(githubRepositoryRepository.findById(repositoryId).orElseThrow())
                .githubPrNumber(githubPrNumber)
                .title("Phase 1 risk test PR")
                .author("phase1-test")
                .status("OPEN")
                .build());

        affectedEndpointRepository.save(PrAffectedEndpoint.builder()
                .pullRequest(pullRequest)
                .endpoint(endpointRepository.findById(endpointId).orElseThrow())
                .build());
        return pullRequest.getId();
    }
}
