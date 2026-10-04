package com.endpointguard.audit.repository;

import com.endpointguard.audit.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findTop100ByActorOrderByCreatedAtDesc(String actor);
}