package com.endpointguard.risk.controller;

import com.endpointguard.risk.dto.PostMergeMonitoringResponse;
import com.endpointguard.risk.service.PostMergeMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/monitoring")
@RequiredArgsConstructor
public class PostMergeMonitoringController {

    private final PostMergeMonitoringService monitoringService;

    @GetMapping
    public List<PostMergeMonitoringResponse> list(
            @PathVariable Long projectId, Authentication authentication) {
        return monitoringService.listForProject(projectId, authentication.getName());
    }
}