package com.endpointguard.metrics.controller;

import com.endpointguard.common.time.UtcDateTime;
import com.endpointguard.metrics.dto.TrafficMetricResponse;
import com.endpointguard.metrics.service.MetricsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/endpoints/{endpointId}/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsQueryService metricsQueryService;

    @GetMapping
    public List<TrafficMetricResponse> getMetrics(
            @PathVariable Long endpointId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            Authentication authentication) {
        LocalDateTime effectiveFrom = from != null ? from : UtcDateTime.now().minusHours(24);
        LocalDateTime effectiveTo = to != null ? to : UtcDateTime.now();
        return metricsQueryService.getMetrics(endpointId, effectiveFrom, effectiveTo, authentication.getName())
                .stream().map(TrafficMetricResponse::from).toList();
    }
}
