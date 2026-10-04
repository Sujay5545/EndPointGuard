package com.endpointguard.demoapi.model;

import java.time.LocalDateTime;

public record Payment(Long id, Long orderId, double amount, String status, String method, LocalDateTime processedAt) {}
