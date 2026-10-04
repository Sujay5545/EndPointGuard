package com.endpointguard.project.service;

import com.endpointguard.auth.domain.User;
import com.endpointguard.auth.repository.UserRepository;
import com.endpointguard.common.exception.ResourceNotFoundException;
import com.endpointguard.project.domain.Project;
import com.endpointguard.project.dto.CreateProjectRequest;
import com.endpointguard.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    @Transactional
    public Project create(String ownerEmail, CreateProjectRequest request) {
        User owner = userRepository.findByEmail(ownerEmail)
            .orElseThrow(() -> new ResourceNotFoundException("User with email " + ownerEmail + " not found"));
        return projectRepository.save(Project.builder()
                .name(request.name())
                .owner(owner)
                .build());
    }

    @Transactional(readOnly = true)
    public List<Project> list(String ownerEmail) {
        return projectRepository.findByOwnerEmailOrderByCreatedAtDesc(ownerEmail);
    }

    @Transactional(readOnly = true)
    public Project getOwned(Long projectId, String ownerEmail) {
        return projectRepository.findByIdAndOwnerEmail(projectId, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }
}