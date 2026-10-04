package com.endpointguard.demoapi.metrics;

import com.endpointguard.demoapi.DemoApiLimits;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class EndpointStatsRegistry {

    private final ConcurrentHashMap<String, AtomicLong> requestCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> error4xxCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> error5xxCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> totalLatencyMs = new ConcurrentHashMap<>();

    public void recordRequest(String endpointKey, int statusCode, long latencyMs) {
        requestCounts.computeIfAbsent(endpointKey, k -> new AtomicLong(0)).incrementAndGet();
        totalLatencyMs.computeIfAbsent(endpointKey, k -> new AtomicLong(0)).addAndGet(latencyMs);
        if (statusCode >= 400 && statusCode < 500) {
            error4xxCounts.computeIfAbsent(endpointKey, k -> new AtomicLong(0)).incrementAndGet();
        } else if (statusCode >= 500) {
            error5xxCounts.computeIfAbsent(endpointKey, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public EndpointSnapshot getAndResetSnapshot(String endpointKey) {
        long requests = getAndReset(requestCounts, endpointKey);
        long err4xx = getAndReset(error4xxCounts, endpointKey);
        long err5xx = getAndReset(error5xxCounts, endpointKey);
        long totalLatency = getAndReset(totalLatencyMs, endpointKey);
        double avgLatency = requests > 0 ? (double) totalLatency / requests : 0;
        double rateLimitCapacity = endpointKey.equals("POST:/api/payments")
            ? DemoApiLimits.PAYMENT_REQUESTS_PER_MINUTE
            : 0;
        double rateLimitPct = rateLimitCapacity > 0
            ? Math.min((double) requests / rateLimitCapacity * 100, 100)
            : 0;
        return new EndpointSnapshot(endpointKey, requests, err4xx, err5xx, avgLatency, rateLimitPct);
    }

    private long getAndReset(ConcurrentHashMap<String, AtomicLong> map, String key) {
        AtomicLong counter = map.get(key);
        return counter != null ? counter.getAndSet(0) : 0;
    }

    public record EndpointSnapshot(
        String endpointKey,
        long requestCount,
        long error4xxCount,
        long error5xxCount,
        double avgLatencyMs,
        double rateLimitUtilizationPct
    ) {}
}
