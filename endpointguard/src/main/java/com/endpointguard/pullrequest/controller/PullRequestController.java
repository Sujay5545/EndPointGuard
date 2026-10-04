package com.endpointguard.pullrequest.controller;

import com.endpointguard.pullrequest.dto.PullRequestResponse;
import com.endpointguard.pullrequest.dto.PullRequestPageResponse;
import com.endpointguard.pullrequest.service.PullRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/projects/{projectId}/pull-requests")
@RequiredArgsConstructor
public class PullRequestController {

    private final PullRequestService pullRequestService;

    @GetMapping
    public PullRequestPageResponse list(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        return pullRequestService.listOwnedForProject(projectId, authentication.getName(), page, size, status);
    }

    @GetMapping("/{pullRequestId}")
    public PullRequestResponse.Detail get(
            @PathVariable Long projectId,
            @PathVariable Long pullRequestId,
            Authentication authentication) {
        return pullRequestService.getOwnedDetail(projectId, pullRequestId, authentication.getName());
    }
}
