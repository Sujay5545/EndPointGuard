package com.endpointguard.pullrequest.domain;

import com.endpointguard.common.time.UtcDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "webhook_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "github_delivery_id", nullable = false, unique = true, length = 255)
    private String githubDeliveryId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "repository_full_name", length = 255)
    private String repositoryFullName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private JsonNode payload = JsonNodeFactory.instance.objectNode();

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "processing_status", nullable = false, length = 50)
    @Builder.Default
    private String processingStatus = "RECEIVED";

    @PrePersist
    protected void onCreate() {
        if (receivedAt == null) receivedAt = UtcDateTime.now();
    }
}