package com.endpointguard.audit.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record AuditLogResponse(
        Long id,
        String entityType,
        String entityId,
        String action,
        String actor,
        Map<String, Object> details,
        LocalDateTime createdAt) {
}