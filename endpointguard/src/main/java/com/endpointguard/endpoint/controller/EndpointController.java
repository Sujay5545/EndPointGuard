package com.endpointguard.endpoint.controller;

import com.endpointguard.endpoint.dto.EndpointResponse;
import com.endpointguard.endpoint.dto.RegisterEndpointRequest;
import com.endpointguard.endpoint.service.EndpointService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/endpoints")
@RequiredArgsConstructor
public class EndpointController {

    private final EndpointService endpointService;

    @GetMapping
    public List<EndpointResponse> list(
            @PathVariable Long repositoryId,
            Authentication authentication) {
        return endpointService.list(repositoryId, authentication.getName()).stream()
                .map(endpoint -> EndpointResponse.from(endpoint, endpointService.mappingsFor(endpoint)))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EndpointResponse register(
            @PathVariable Long repositoryId,
            @Valid @RequestBody RegisterEndpointRequest request,
            Authentication authentication) {
        var endpoint = endpointService.register(repositoryId, authentication.getName(), request);
        return EndpointResponse.from(endpoint, endpointService.mappingsFor(endpoint));
    }

    @PutMapping("/{endpointId}")
    public EndpointResponse update(
            @PathVariable Long repositoryId,
            @PathVariable Long endpointId,
            @Valid @RequestBody RegisterEndpointRequest request,
            Authentication authentication) {
        var endpoint = endpointService.update(repositoryId, endpointId, authentication.getName(), request);
        return EndpointResponse.from(endpoint, endpointService.mappingsFor(endpoint));
    }

    @DeleteMapping("/{endpointId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long repositoryId,
            @PathVariable Long endpointId,
            Authentication authentication) {
        endpointService.delete(repositoryId, endpointId, authentication.getName());
    }

    @GetMapping("/resolve")
    public List<EndpointResponse> resolve(
            @PathVariable Long repositoryId,
            @RequestParam String filePath,
            Authentication authentication) {
        return endpointService.resolve(repositoryId, authentication.getName(), filePath).stream()
                .map(endpoint -> EndpointResponse.from(endpoint, endpointService.mappingsFor(endpoint)))
                .toList();
    }
}