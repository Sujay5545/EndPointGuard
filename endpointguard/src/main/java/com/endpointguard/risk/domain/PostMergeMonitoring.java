package com.endpointguard.risk.domain;

import com.endpointguard.common.time.UtcDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "post_merge_monitoring")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostMergeMonitoring {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pull_request_id", nullable = false)
    private Long pullRequestId;

    @Column(name = "endpoint_id", nullable = false)
    private Long endpointId;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    @Column(name = "baseline_error_rate")
    private Double baselineErrorRate;

    @Column(name = "observed_error_rate")
    private Double observedErrorRate;

    @Column(name = "baseline_latency_ms")
    private Double baselineLatencyMs;

    @Column(name = "observed_latency_ms")
    private Double observedLatencyMs;

    @Column(name = "baseline_request_count")
    private Long baselineRequestCount;

    @Column(name = "observed_request_count")
    private Long observedRequestCount;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String verdict = "PENDING";

    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = UtcDateTime.now();
        }
    }
}
