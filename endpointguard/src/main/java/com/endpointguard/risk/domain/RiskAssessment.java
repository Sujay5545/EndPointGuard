package com.endpointguard.risk.domain;

import com.endpointguard.common.time.UtcDateTime;
import jakarta.persistence.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "risk_assessments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pull_request_id", nullable = false)
    private Long pullRequestId;

    @Column(nullable = false)
    private Double score;

    @Column(nullable = false, length = 20)
    private String tier;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factor_breakdown", nullable = false, columnDefinition = "JSONB")
    private JsonNode factorBreakdown;

    @Column(name = "evaluation_status", nullable = false, length = 32)
    @Builder.Default
    private String evaluationStatus = "EVALUATED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_quality_notes", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private JsonNode dataQualityNotes = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();

    @PrePersist
    protected void onCreate() {
        if (computedAt == null) {
            computedAt = UtcDateTime.now();
        }
    }
}
