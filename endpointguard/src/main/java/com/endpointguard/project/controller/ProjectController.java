package com.endpointguard.project.controller;

import com.endpointguard.project.dto.CreateProjectRequest;
import com.endpointguard.project.dto.ProjectResponse;
import com.endpointguard.project.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public List<ProjectResponse> list(Authentication authentication) {
        return projectService.list(authentication.getName()).stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(
            @Valid @RequestBody CreateProjectRequest request,
            Authentication authentication) {
        return ProjectResponse.from(projectService.create(authentication.getName(), request));
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable Long projectId, Authentication authentication) {
        return ProjectResponse.from(projectService.getOwned(projectId, authentication.getName()));
    }
}