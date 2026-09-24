package com.amrap.application.auth;

import com.amrap.application.common.AppException;
import com.amrap.domain.user.User;
import com.amrap.persistence.user.UserRepository;
import com.amrap.security.JwtService;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public LoginResult login(LoginCommand command) {
        String email = normalizeEmail(command.email());
        User user = userRepository
                .findByEmail(email)
                .orElseThrow(() -> new AppException(401, "Invalid email or password"));

        if (!user.isActive()) {
            throw new AppException(403, "Account is deactivated");
        }

        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            throw new AppException(401, "Invalid email or password");
        }

        Instant now = Instant.now();
        user.setLastLoginAt(now);
        userRepository.save(user);

        String token = jwtService.generateToken(
                user.getId(), user.getEmail(), user.getRole(), user.getDisplayName());
        return new LoginResult(token, toSummary(user));
    }

    @Transactional(readOnly = true)
    public UserSummary me(java.util.UUID userId) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new AppException(404, "User not found"));
        return toSummary(user);
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new AppException(400, "Email is required");
        }
        return email.trim().toLowerCase();
    }

    public static UserSummary toSummary(User user) {
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getCreatedBy(),
                user.isActive(),
                user.getLastLoginAt(),
                user.getCreatedAt());
    }
}
