package com.amrap.web.auth;

import com.amrap.application.auth.AuthService;
import com.amrap.application.auth.LoginCommand;
import com.amrap.application.auth.LoginResult;
import com.amrap.application.auth.UserSummary;
import com.amrap.security.AuthenticatedUser;
import com.amrap.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResult> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authService.login(new LoginCommand(request.email(), request.password()));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/me")
    public ResponseEntity<UserSummary> me() {
        AuthenticatedUser current = SecurityUtils.requireCurrentUser();
        return ResponseEntity.ok(authService.me(current.getId()));
    }
}
