package com.endpointguard.demoapi.metrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class MetricsInterceptor implements HandlerInterceptor {

    private final EndpointStatsRegistry statsRegistry;
    private static final String START_TIME_ATTR = "requestStartTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
        if (startTime == null) return;
        long latency = System.currentTimeMillis() - startTime;
        String endpointKey = request.getMethod() + ":" + normalizeUri(request.getRequestURI());
        statsRegistry.recordRequest(endpointKey, response.getStatus(), latency);
    }

    private String normalizeUri(String uri) {
        // Normalize UUIDs: /api/payments/uuid -> /api/payments/{id}
        return uri.replaceAll("/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "/{id}")
                  .replaceAll("/\\d+", "/{id}");
    }
}
