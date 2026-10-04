package com.endpointguard.repository.controller;

import com.endpointguard.repository.dto.LinkRepositoryRequest;
import com.endpointguard.repository.dto.RepositoryResponse;
import com.endpointguard.repository.service.GithubRepositoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/repositories")
@RequiredArgsConstructor
public class GithubRepositoryController {

    private final GithubRepositoryService repositoryService;

    @GetMapping
    public List<RepositoryResponse> list(
            @PathVariable Long projectId,
            Authentication authentication) {
        return repositoryService.list(projectId, authentication.getName()).stream()
                .map(RepositoryResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RepositoryResponse link(
            @PathVariable Long projectId,
            @Valid @RequestBody LinkRepositoryRequest request,
            Authentication authentication) {
        return RepositoryResponse.from(
                repositoryService.link(projectId, authentication.getName(), request));
    }

    @PutMapping("/{repositoryId}")
    public RepositoryResponse update(
            @PathVariable Long projectId,
            @PathVariable Long repositoryId,
            @Valid @RequestBody LinkRepositoryRequest request,
            Authentication authentication) {
        return RepositoryResponse.from(repositoryService.update(
                projectId, repositoryId, authentication.getName(), request));
    }

    @DeleteMapping("/{repositoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long projectId,
            @PathVariable Long repositoryId,
            Authentication authentication) {
        repositoryService.delete(projectId, repositoryId, authentication.getName());
    }
}