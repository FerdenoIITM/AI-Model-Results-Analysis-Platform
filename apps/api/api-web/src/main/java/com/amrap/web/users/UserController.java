package com.amrap.web.users;

import com.amrap.application.auth.UserSummary;
import com.amrap.application.users.CreateUserCommand;
import com.amrap.application.users.UpdateUserCommand;
import com.amrap.application.users.UserManagementService;
import com.amrap.security.AuthenticatedUser;
import com.amrap.security.SecurityUtils;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserManagementService userManagementService;

    public UserController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping
    public ResponseEntity<List<UserSummary>> list() {
        AuthenticatedUser actor = SecurityUtils.requireCurrentUser();
        return ResponseEntity.ok(userManagementService.listUsers(actor));
    }

    @PostMapping
    public ResponseEntity<UserSummary> create(@Valid @RequestBody CreateUserRequest request) {
        AuthenticatedUser actor = SecurityUtils.requireCurrentUser();
        UserSummary created = userManagementService.createUser(
                actor,
                new CreateUserCommand(
                        request.email(), request.password(), request.displayName(), request.role()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserSummary> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        AuthenticatedUser actor = SecurityUtils.requireCurrentUser();
        UserSummary updated = userManagementService.updateUser(
                actor,
                id,
                new UpdateUserCommand(
                        request.displayName(), request.password(), request.role(), request.active()));
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<UserSummary> deactivate(@PathVariable UUID id) {
        AuthenticatedUser actor = SecurityUtils.requireCurrentUser();
        return ResponseEntity.ok(userManagementService.deactivateUser(actor, id));
    }
}
