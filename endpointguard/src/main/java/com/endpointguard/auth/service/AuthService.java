package com.endpointguard.auth.service;

import com.endpointguard.audit.service.AuditLogService;
import com.endpointguard.auth.domain.User;
import com.endpointguard.auth.dto.ProfileResponse;
import com.endpointguard.auth.repository.UserRepository;
import com.endpointguard.common.exception.BadRequestException;
import com.endpointguard.common.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsServiceImpl userDetailsService;
    private final AuditLogService auditLogService;

    @Transactional
    public String register(String email, String password, User.Role role) {
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered: " + email);
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
            .role(User.Role.MEMBER)
                .build();
        userRepository.save(user);
        log.info("Registered new user: {}", email);
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);
        return jwtService.generateToken(userDetails);
    }

    public String login(String email, String password) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password)
        );
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);
        return jwtService.generateToken(userDetails);
    }

    @Transactional(readOnly = true)
    public ProfileResponse profile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("Account profile is unavailable"));
        return new ProfileResponse(user.getEmail(), user.getRole(), user.getCreatedAt());
    }

    @Transactional
    public String changePassword(String email, String currentPassword, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("Account profile is unavailable"));
        if (newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new BadRequestException("New password must be at most 72 UTF-8 bytes");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BadRequestException("Choose a password different from the current password");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        auditLogService.record("USER", user.getId().toString(), "PASSWORD_CHANGED", email,
                java.util.Map.of("changed", true));
        return jwtService.generateToken(userDetailsService.loadUserByUsername(email));
    }
}
