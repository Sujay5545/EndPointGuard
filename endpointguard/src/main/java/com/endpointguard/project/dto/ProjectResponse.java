package com.endpointguard.project.dto;

import com.endpointguard.project.domain.Project;

import java.time.LocalDateTime;

public record ProjectResponse(Long id, String name, String ownerEmail, LocalDateTime createdAt) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getOwner().getEmail(),
                project.getCreatedAt()
        );
    }
}