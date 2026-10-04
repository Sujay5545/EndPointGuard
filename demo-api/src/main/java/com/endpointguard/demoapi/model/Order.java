package com.endpointguard.demoapi.model;

import java.time.LocalDateTime;
import java.util.List;

public record Order(Long id, String customerId, List<Long> productIds, double total, String status, LocalDateTime createdAt) {}
