package com.endpointguard.endpoint.dto;

import com.endpointguard.endpoint.domain.Endpoint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RegisterEndpointRequest(
        @NotBlank @Pattern(regexp = "(?i)^(GET|POST|PUT|PATCH|DELETE)$", message = "Method must be GET, POST, PUT, PATCH, or DELETE") @Size(max = 10) String method,
        @NotBlank @Pattern(regexp = "^/\\S*$", message = "Path pattern must start with '/' and contain no whitespace") @Size(max = 500) String pathPattern,
        Endpoint.Criticality criticality,
        @NotEmpty List<@NotBlank @Size(max = 500) String> sourcePatterns
) {
}