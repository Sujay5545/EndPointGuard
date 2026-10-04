package com.endpointguard.endpoint.dto;

import com.endpointguard.endpoint.domain.Endpoint;
import com.endpointguard.endpoint.domain.EndpointMapping;

import java.time.LocalDateTime;
import java.util.List;

public record EndpointResponse(
        Long id,
        Long projectId,
    Long repositoryId,
        String method,
        String pathPattern,
        Endpoint.Criticality criticality,
        List<String> sourcePatterns,
        LocalDateTime createdAt) {

    public static EndpointResponse from(Endpoint endpoint, List<EndpointMapping> mappings) {
        return new EndpointResponse(
                endpoint.getId(),
                endpoint.getProjectId(),
                endpoint.getRepositoryId(),
                endpoint.getMethod(),
                endpoint.getPathPattern(),
                endpoint.getCriticality(),
                mappings.stream().map(EndpointMapping::getSourcePattern).toList(),
                endpoint.getCreatedAt()
        );
    }
}