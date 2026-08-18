package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.ControlPlaneModels.UserPolicyMigrationResponse;
import com.example.nodecontrol.security.ControlSessionService;
import com.example.nodecontrol.service.NodeUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/control/user-policy-migrations")
public class UserPolicyMigrationController {

    private final NodeUserService userService;
    private final ControlSessionService sessionService;

    public UserPolicyMigrationController(NodeUserService userService,
                                         ControlSessionService sessionService) {
        this.userService = userService;
        this.sessionService = sessionService;
    }

    @PostMapping("/nodes/{nodeId}/defaults")
    public ResponseEntity<UserPolicyMigrationResponse> migrateDefaults(
            @PathVariable UUID nodeId,
            HttpServletRequest request) {
        UUID actor = sessionService.authenticatedSession(request)
                .map(ControlSessionService.AuthenticatedSession::userId)
                .orElse(null);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(userService.migrateAllUsersToDefaultPolicy(nodeId, actor));
    }
}
