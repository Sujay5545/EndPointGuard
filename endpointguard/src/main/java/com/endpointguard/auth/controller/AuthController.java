package com.endpointguard.auth.controller;

import com.endpointguard.auth.dto.AuthResponse;
import com.endpointguard.auth.dto.ChangePasswordRequest;
import com.endpointguard.auth.dto.LoginRequest;
import com.endpointguard.auth.dto.ProfileResponse;
import com.endpointguard.auth.dto.RegisterRequest;
import com.endpointguard.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
//No risk
    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return new AuthResponse(authService.register(request.email(), request.password(), request.role()));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return new AuthResponse(authService.login(request.email(), request.password()));
    }

    @GetMapping("/profile")
    public ProfileResponse profile(Authentication authentication) {
        return authService.profile(authentication.getName());
    }

    @PutMapping("/password")
    public AuthResponse changePassword(
            @Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        return new AuthResponse(authService.changePassword(authentication.getName(),
                request.currentPassword(), request.newPassword()));
    }
}
