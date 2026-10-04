package com.endpointguard.metrics.domain;

import com.endpointguard.common.time.UtcDateTime;
import com.endpointguard.endpoint.domain.Endpoint;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "traffic_metrics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrafficMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private Endpoint endpoint;

    @Column(name = "bucket_start", nullable = false)
    private LocalDateTime bucketStart;

    @Column(name = "request_count", nullable = false)
    private Long requestCount;

    @Column(name = "error_4xx_count", nullable = false)
    private Long error4xxCount;

    @Column(name = "error_5xx_count", nullable = false)
    private Long error5xxCount;

    @Column(name = "avg_latency_ms", nullable = false)
    private Double avgLatencyMs;

    @Column(name = "rate_limit_utilization_pct", nullable = false)
    private Double rateLimitUtilizationPct;

    @Column(name = "collected_at")
    private LocalDateTime collectedAt;

    @PrePersist
    protected void onCreate() {
        collectedAt = UtcDateTime.now();
    }
}
