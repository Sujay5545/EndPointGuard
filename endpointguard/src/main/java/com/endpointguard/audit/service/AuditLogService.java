package com.endpointguard.audit.service;

import com.endpointguard.audit.domain.AuditLog;
import com.endpointguard.audit.dto.AuditLogResponse;
import com.endpointguard.audit.repository.AuditLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final TypeReference<Map<String, Object>> DETAILS_TYPE = new TypeReference<>() { };

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void record(String entityType, String entityId, String action, String actor, Map<String, ?> details) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .entityType(entityType)
                    .entityId(entityId)
                    .action(action)
                    .actor(actor)
                    .details(objectMapper.valueToTree(details == null ? Map.of() : details))
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to record audit event", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> listForActor(String actor) {
        return auditLogRepository.findTop100ByActorOrderByCreatedAtDesc(actor).stream()
                .map(log -> new AuditLogResponse(log.getId(), log.getEntityType(), log.getEntityId(),
                        log.getAction(), log.getActor(), parseDetails(log.getDetails()), log.getCreatedAt()))
                .toList();
    }

    private Map<String, Object> parseDetails(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || !value.isObject()) return Map.of();
        return objectMapper.convertValue(value, DETAILS_TYPE);
    }
}