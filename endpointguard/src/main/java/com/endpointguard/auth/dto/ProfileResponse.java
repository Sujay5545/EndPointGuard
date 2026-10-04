package com.endpointguard.auth.dto;

import com.endpointguard.auth.domain.User;

import java.time.LocalDateTime;

public record ProfileResponse(String email, User.Role role, LocalDateTime createdAt) {
}