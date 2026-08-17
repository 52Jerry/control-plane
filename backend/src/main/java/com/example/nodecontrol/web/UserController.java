package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.ControlPlaneModels.BatchConnectionResult;
import com.example.nodecontrol.dto.ControlPlaneModels.BatchConnectionsRequest;
import com.example.nodecontrol.dto.RemoteModels.BindProxyRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.OperationResponse;
import com.example.nodecontrol.dto.RemoteModels.ProxyDetails;
import com.example.nodecontrol.dto.RemoteModels.TrafficResponse;
import com.example.nodecontrol.dto.RemoteModels.UpdateUserPolicyRequest;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.UserPage;
import com.example.nodecontrol.dto.RemoteModels.UserPolicyResponse;
import com.example.nodecontrol.dto.RemoteModels.UserSummary;
import com.example.nodecontrol.service.NodeUserService;
import com.example.nodecontrol.security.ControlSessionService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/control/nodes/{nodeId}")
public class UserController {

    private final NodeUserService userService;
    private final ControlSessionService sessionService;

    public UserController(NodeUserService userService, ControlSessionService sessionService) {
        this.userService = userService;
        this.sessionService = sessionService;
    }

    @GetMapping("/users")
    public ResponseEntity<UserPage> users(
            @PathVariable UUID nodeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String ip,
            @RequestParam(defaultValue = "createdDesc") String sort,
            @RequestParam(defaultValue = "false") boolean refresh,
            HttpServletRequest servletRequest
    ) {
        boolean includeAccessCredentials = canViewSensitive(servletRequest);
        return noStore(refresh
                ? userService.listUsers(
                        nodeId, page, pageSize, keyword, ip, sort, includeAccessCredentials, true)
                : userService.listUsers(
                        nodeId, page, pageSize, keyword, ip, sort, includeAccessCredentials));
    }

    @GetMapping("/users/export")
    public ResponseEntity<List<UserSummary>> exportUsers(
            @PathVariable UUID nodeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String ip,
            @RequestParam(defaultValue = "createdDesc") String sort,
            HttpServletRequest servletRequest
    ) {
        return noStore(userService.listUsersForExport(
                nodeId, keyword, ip, sort, canViewSensitive(servletRequest)));
    }

    @PostMapping("/users")
    public ResponseEntity<CreateUserResponse> createUser(
            @PathVariable UUID nodeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateUserRequest request,
            HttpServletRequest servletRequest
    ) {
        UUID actor = actor(servletRequest);
        return noStore(actor == null
                ? userService.createUser(nodeId, request, idempotencyKey)
                : userService.createUser(nodeId, request, idempotencyKey, actor));
    }

    @GetMapping("/users/{userId}/connections")
    public ResponseEntity<UserConnection> connections(@PathVariable UUID nodeId, @PathVariable String userId) {
        return noStore(userService.getConnections(nodeId, userId));
    }

    @PostMapping("/users/connections/batch")
    public ResponseEntity<List<BatchConnectionResult>> connectionsBatch(
            @PathVariable UUID nodeId,
            @Valid @RequestBody BatchConnectionsRequest request
    ) {
        return noStore(userService.getConnectionsBatch(nodeId, request.userIds()));
    }

    @GetMapping("/users/{userId}/proxy")
    public ResponseEntity<ProxyDetails> proxy(@PathVariable UUID nodeId, @PathVariable String userId) {
        return noStore(userService.getProxy(nodeId, userId));
    }

    @GetMapping("/users/{userId}/traffic")
    public TrafficResponse traffic(@PathVariable UUID nodeId, @PathVariable String userId) {
        return userService.getTraffic(nodeId, userId);
    }

    @PatchMapping("/users/{userId}/policy")
    public ResponseEntity<UserPolicyResponse> updatePolicy(
            @PathVariable UUID nodeId,
            @PathVariable String userId,
            @Valid @RequestBody UpdateUserPolicyRequest request,
            HttpServletRequest servletRequest
    ) {
        return noStore(userService.updatePolicy(nodeId, userId, request, actor(servletRequest)));
    }

    @PostMapping("/users/bind-proxy")
    public OperationResponse bindProxy(
            @PathVariable UUID nodeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BindProxyRequest request,
            HttpServletRequest servletRequest
    ) {
        UUID actor = actor(servletRequest);
        return actor == null
                ? userService.bindProxy(nodeId, request, idempotencyKey)
                : userService.bindProxy(nodeId, request, idempotencyKey, actor);
    }

    @DeleteMapping("/users/{userId}")
    public OperationResponse deleteUser(
            @PathVariable UUID nodeId,
            @PathVariable String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        UUID actor = actor(servletRequest);
        return actor == null
                ? userService.deleteUser(nodeId, userId, idempotencyKey)
                : userService.deleteUser(nodeId, userId, idempotencyKey, actor);
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private UUID actor(HttpServletRequest request) {
        return sessionService.authenticatedSession(request).map(ControlSessionService.AuthenticatedSession::userId).orElse(null);
    }

    private boolean canViewSensitive(HttpServletRequest request) {
        Object role = request.getAttribute("control.role");
        return role == null || java.util.List.of("ADMIN", "NODE_OPS", "PROVISIONER")
                .contains(String.valueOf(role));
    }
}

