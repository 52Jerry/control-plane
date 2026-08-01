package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.RemoteModels.BindProxyRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.OperationResponse;
import com.example.nodecontrol.dto.RemoteModels.TrafficResponse;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.UserPage;
import com.example.nodecontrol.service.NodeUserService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

@RestController
@RequestMapping("/api/control/nodes/{nodeId}")
public class UserController {

    private final NodeUserService userService;

    public UserController(NodeUserService userService) {
        this.userService = userService;
    }

    @GetMapping("/users")
    public UserPage users(
            @PathVariable UUID nodeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword
    ) {
        return userService.listUsers(nodeId, page, pageSize, keyword);
    }

    @PostMapping("/users")
    public ResponseEntity<CreateUserResponse> createUser(
            @PathVariable UUID nodeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateUserRequest request
    ) {
        return noStore(userService.createUser(nodeId, request, idempotencyKey));
    }

    @GetMapping("/users/{userId}/connections")
    public ResponseEntity<UserConnection> connections(@PathVariable UUID nodeId, @PathVariable String userId) {
        return noStore(userService.getConnections(nodeId, userId));
    }

    @GetMapping("/users/{userId}/traffic")
    public TrafficResponse traffic(@PathVariable UUID nodeId, @PathVariable String userId) {
        return userService.getTraffic(nodeId, userId);
    }

    @PostMapping("/users/bind-proxy")
    public OperationResponse bindProxy(
            @PathVariable UUID nodeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BindProxyRequest request
    ) {
        return userService.bindProxy(nodeId, request, idempotencyKey);
    }

    @DeleteMapping("/users/{userId}")
    public OperationResponse deleteUser(
            @PathVariable UUID nodeId,
            @PathVariable String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return userService.deleteUser(nodeId, userId, idempotencyKey);
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}

