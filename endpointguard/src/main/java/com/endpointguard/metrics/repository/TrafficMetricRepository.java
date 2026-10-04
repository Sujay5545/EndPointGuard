package com.endpointguard.metrics.repository;

import com.endpointguard.metrics.domain.TrafficMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrafficMetricRepository extends JpaRepository<TrafficMetric, Long> {

    void deleteByEndpointId(Long endpointId);

    List<TrafficMetric> findByEndpointIdAndBucketStartBetweenOrderByBucketStartAsc(
            Long endpointId, LocalDateTime from, LocalDateTime to);

    Optional<TrafficMetric> findByEndpointIdAndBucketStart(Long endpointId, LocalDateTime bucketStart);

    @Query("""
        SELECT AVG(tm.error5xxCount * 1.0 / NULLIF(tm.requestCount, 0))
        FROM TrafficMetric tm
        WHERE tm.endpoint.id = :endpointId
        AND tm.bucketStart >= :from
        AND tm.bucketStart < :to
        """)
    Double computeAvgErrorRate(
            @Param("endpointId") Long endpointId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
        SELECT AVG(tm.avgLatencyMs)
        FROM TrafficMetric tm
        WHERE tm.endpoint.id = :endpointId
        AND tm.bucketStart >= :from
        AND tm.bucketStart < :to
        """)
    Double computeAvgLatency(
            @Param("endpointId") Long endpointId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
        SELECT COALESCE(SUM(tm.requestCount), 0)
        FROM TrafficMetric tm
        WHERE tm.endpoint.id = :endpointId
        AND tm.bucketStart >= :from
        AND tm.bucketStart < :to
        """)
    Long sumRequestCount(
            @Param("endpointId") Long endpointId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
        SELECT AVG(tm.rateLimitUtilizationPct)
        FROM TrafficMetric tm
        WHERE tm.endpoint.id = :endpointId
        AND tm.bucketStart >= :from
        AND tm.bucketStart < :to
        """)
    Double computeAvgRateLimitUtilization(
            @Param("endpointId") Long endpointId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
        SELECT tm FROM TrafficMetric tm
        WHERE tm.endpoint.id = :endpointId
        ORDER BY tm.bucketStart DESC
        LIMIT 1
        """)
    Optional<TrafficMetric> findLatestByEndpointId(@Param("endpointId") Long endpointId);
}
