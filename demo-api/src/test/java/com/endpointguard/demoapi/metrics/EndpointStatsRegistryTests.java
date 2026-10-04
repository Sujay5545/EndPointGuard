package com.endpointguard.demoapi.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointStatsRegistryTests {

    @Test
    void reportsPaymentCapacityUtilizationSeparatelyFromRateLimitErrors() {
        EndpointStatsRegistry registry = new EndpointStatsRegistry();
        for (int request = 0; request < 100; request++) {
            registry.recordRequest("POST:/api/payments", 201, 12);
        }
        registry.recordRequest("POST:/api/payments", 429, 12);

        EndpointStatsRegistry.EndpointSnapshot snapshot = registry.getAndResetSnapshot("POST:/api/payments");

        assertThat(snapshot.requestCount()).isEqualTo(101);
        assertThat(snapshot.error4xxCount()).isEqualTo(1);
        assertThat(snapshot.error5xxCount()).isZero();
        assertThat(snapshot.rateLimitUtilizationPct()).isEqualTo(100.0);
    }

    @Test
    void reportsHalfCapacityForFiftyPaymentRequests() {
        EndpointStatsRegistry registry = new EndpointStatsRegistry();
        for (int request = 0; request < 50; request++) {
            registry.recordRequest("POST:/api/payments", 201, 12);
        }

        assertThat(registry.getAndResetSnapshot("POST:/api/payments").rateLimitUtilizationPct())
                .isEqualTo(50.0);
    }
}
