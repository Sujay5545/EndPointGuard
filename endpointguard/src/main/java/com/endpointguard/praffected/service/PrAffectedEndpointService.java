package com.endpointguard.praffected.service;

import com.endpointguard.endpoint.domain.Endpoint;
import com.endpointguard.endpoint.domain.EndpointMapping;
import com.endpointguard.endpoint.repository.EndpointMappingRepository;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.endpoint.service.EndpointSourcePatternMatcher;
import com.endpointguard.praffected.domain.PrAffectedEndpoint;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.pullrequest.domain.PrChangedFile;
import com.endpointguard.pullrequest.domain.PullRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PrAffectedEndpointService {

    private final EndpointRepository endpointRepository;
    private final EndpointMappingRepository endpointMappingRepository;
    private final PrAffectedEndpointRepository prAffectedEndpointRepository;
    private final EndpointSourcePatternMatcher sourcePatternMatcher;

    public PrAffectedEndpointService(
            EndpointRepository endpointRepository,
            EndpointMappingRepository endpointMappingRepository,
            PrAffectedEndpointRepository prAffectedEndpointRepository,
            EndpointSourcePatternMatcher sourcePatternMatcher) {
        this.endpointRepository = endpointRepository;
        this.endpointMappingRepository = endpointMappingRepository;
        this.prAffectedEndpointRepository = prAffectedEndpointRepository;
        this.sourcePatternMatcher = sourcePatternMatcher;
    }

    @Transactional
    public void mapChangedFilesToAffectedEndpoints(PullRequest pullRequest, Iterable<PrChangedFile> changedFiles) {
        Long projectId = pullRequest.getRepository().getProject().getId();
        Long repositoryId = pullRequest.getRepository().getId();
        Set<Endpoint> affectedEndpoints = new LinkedHashSet<>();

        for (PrChangedFile changedFile : changedFiles) {
            String path = changedFile.getFilePath();
            if (path == null || path.isBlank()) {
                continue;
            }

            String normalized = path.replace('\\', '/');
            for (Endpoint endpoint : endpointRepository.findByProjectIdOrderByPathPatternAsc(projectId)) {
                if (endpoint.getRepositoryId() != null && !endpoint.getRepositoryId().equals(repositoryId)) {
                    continue;
                }
                boolean matches = endpointMappingRepository.findByEndpointIdOrderBySourcePatternAsc(endpoint.getId())
                        .stream()
                        .map(EndpointMapping::getSourcePattern)
                        .anyMatch(pattern -> sourcePatternMatcher.matches(normalized, pattern));
                if (matches) {
                    affectedEndpoints.add(endpoint);
                }
            }
        }

        prAffectedEndpointRepository.deleteByPullRequest_Id(pullRequest.getId());
        affectedEndpoints.stream()
            .sorted(Comparator.comparing(Endpoint::getId))
            .map(endpoint -> PrAffectedEndpoint.builder()
                .pullRequest(pullRequest)
                .endpoint(endpoint)
                .build())
            .forEach(prAffectedEndpointRepository::save);
    }

    @Transactional(readOnly = true)
    public List<Endpoint> findAffectedEndpointsForPullRequest(PullRequest pullRequest) {
        return prAffectedEndpointRepository.findByPullRequestOrderByEndpointAsc(pullRequest).stream()
                .map(PrAffectedEndpoint::getEndpoint)
                .collect(Collectors.toList());
    }

}
