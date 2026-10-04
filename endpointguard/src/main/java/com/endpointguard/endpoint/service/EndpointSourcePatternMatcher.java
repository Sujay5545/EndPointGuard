package com.endpointguard.endpoint.service;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Component
public class EndpointSourcePatternMatcher {

    private final AntPathMatcher pathMatcher = new AntPathMatcher("/");

    public boolean matches(String filePath, String sourcePattern) {
        if (filePath == null || filePath.isBlank() || sourcePattern == null || sourcePattern.isBlank()) {
            return false;
        }
        return pathMatcher.match(normalize(sourcePattern), normalize(filePath));
    }

    private String normalize(String path) {
        return path.replace('\\', '/').replaceFirst("^/+", "");
    }
}