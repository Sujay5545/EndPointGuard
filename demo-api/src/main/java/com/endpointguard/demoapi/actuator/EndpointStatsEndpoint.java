package com.endpointguard.demoapi.actuator;

import com.endpointguard.demoapi.metrics.EndpointStatsRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@Endpoint(id = "endpoint-stats")
@RequiredArgsConstructor
public class EndpointStatsEndpoint {

    private final EndpointStatsRegistry statsRegistry;
    private static final List<String> MONITORED_ENDPOINTS = List.of(
        "POST:/api/payments",
        "GET:/api/payments/{id}",
        "GET:/api/orders",
        "POST:/api/orders",
        "GET:/api/products",
        "POST:/api/products",
        "GET:/api/products/{id}"
    );

    @ReadOperation
    public Map<String, Object> getStats() {
        List<EndpointStatsRegistry.EndpointSnapshot> snapshots = MONITORED_ENDPOINTS.stream()
                .map(statsRegistry::getAndResetSnapshot)
                .toList();
        return Map.of(
            "timestamp", Instant.now().toString(),
            "windowSeconds", 60,
            "endpoints", snapshots
        );
    }
}
