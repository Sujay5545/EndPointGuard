package com.endpointguard.audit.controller;

import com.endpointguard.audit.dto.AuditLogResponse;
import com.endpointguard.audit.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public List<AuditLogResponse> list(Authentication authentication) {
        return auditLogService.listForActor(authentication.getName());
    }
}