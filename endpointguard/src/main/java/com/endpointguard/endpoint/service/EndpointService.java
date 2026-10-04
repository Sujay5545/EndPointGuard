package com.endpointguard.endpoint.service;

import com.endpointguard.common.exception.ResourceNotFoundException;
import com.endpointguard.endpoint.domain.Endpoint;
import com.endpointguard.endpoint.domain.EndpointMapping;
import com.endpointguard.endpoint.dto.RegisterEndpointRequest;
import com.endpointguard.endpoint.repository.EndpointMappingRepository;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.metrics.repository.TrafficMetricRepository;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.project.service.ProjectService;
import com.endpointguard.repository.domain.GithubRepository;
import com.endpointguard.repository.repository.GithubRepositoryRepository;
import com.endpointguard.risk.repository.PostMergeMonitoringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class EndpointService {

    private final EndpointRepository endpointRepository;
    private final EndpointMappingRepository mappingRepository;
        private final TrafficMetricRepository trafficMetricRepository;
        private final PrAffectedEndpointRepository prAffectedEndpointRepository;
        private final PostMergeMonitoringRepository postMergeMonitoringRepository;
    private final GithubRepositoryRepository repositoryRepository;
    private final ProjectService projectService;
        private final EndpointSourcePatternMatcher sourcePatternMatcher;

    @Transactional
    public Endpoint register(Long repositoryId, String ownerEmail, RegisterEndpointRequest request) {
        GithubRepository repository = ownedRepository(repositoryId, ownerEmail);
        Endpoint endpoint = endpointRepository.save(Endpoint.builder()
                .projectId(repository.getProject().getId())
                .repositoryId(repository.getId())
                .method(request.method().toUpperCase())
                .pathPattern(request.pathPattern())
                .criticality(request.criticality() != null
                        ? request.criticality()
                        : Endpoint.Criticality.MEDIUM)
                .build());
        request.sourcePatterns().stream()
                .map(String::trim)
                .map(pattern -> EndpointMapping.builder()
                        .endpoint(endpoint)
                        .sourcePattern(pattern)
                        .build())
                .forEach(mappingRepository::save);
        return endpoint;
    }

    @Transactional(readOnly = true)
    public List<Endpoint> list(Long repositoryId, String ownerEmail) {
        GithubRepository repository = ownedRepository(repositoryId, ownerEmail);
                return endpointsFor(repository);
    }

    @Transactional(readOnly = true)
    public List<Endpoint> resolve(Long repositoryId, String ownerEmail, String filePath) {
        GithubRepository repository = ownedRepository(repositoryId, ownerEmail);
        return endpointsFor(repository).stream()
                .filter(endpoint -> mappingsFor(endpoint).stream()
                        .anyMatch(mapping -> sourcePatternMatcher.matches(filePath, mapping.getSourcePattern())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EndpointMapping> mappingsFor(Endpoint endpoint) {
                return mappingRepository.findByEndpointIdOrderBySourcePatternAsc(endpoint.getId());
        }

    @Transactional
    public Endpoint update(Long repositoryId, Long endpointId, String ownerEmail, RegisterEndpointRequest request) {
        ownedRepository(repositoryId, ownerEmail);
        Endpoint endpoint = endpointRepository.findByIdAndRepositoryId(endpointId, repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint", endpointId));
        endpoint.setMethod(request.method().toUpperCase());
        endpoint.setPathPattern(request.pathPattern());
        endpoint.setCriticality(request.criticality() != null
                ? request.criticality()
                : Endpoint.Criticality.MEDIUM);
        endpointRepository.save(endpoint);
        mappingRepository.deleteByEndpointId(endpointId);
        request.sourcePatterns().stream()
                .map(String::trim)
                .map(pattern -> EndpointMapping.builder()
                        .endpoint(endpoint)
                        .sourcePattern(pattern)
                        .build())
                .forEach(mappingRepository::save);
        return endpoint;
    }

    @Transactional
    public void delete(Long repositoryId, Long endpointId, String ownerEmail) {
        ownedRepository(repositoryId, ownerEmail);
        Endpoint endpoint = endpointRepository.findByIdAndRepositoryId(endpointId, repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint", endpointId));
        trafficMetricRepository.deleteByEndpointId(endpointId);
        mappingRepository.deleteByEndpointId(endpointId);
        prAffectedEndpointRepository.deleteByEndpoint_Id(endpointId);
        postMergeMonitoringRepository.deleteByEndpointId(endpointId);
        endpointRepository.delete(endpoint);
    }

        private List<Endpoint> endpointsFor(GithubRepository repository) {
                return Stream.concat(
                                                endpointRepository.findByRepositoryIdOrderByPathPatternAsc(repository.getId()).stream(),
                                                endpointRepository.findByProjectIdAndRepositoryIdIsNullOrderByPathPatternAsc(repository.getProject().getId()).stream())
                                .sorted(Comparator.comparing(Endpoint::getPathPattern))
                                .toList();
    }

    private GithubRepository ownedRepository(Long repositoryId, String ownerEmail) {
        GithubRepository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));
        projectService.getOwned(repository.getProject().getId(), ownerEmail);
        return repository;
    }
}