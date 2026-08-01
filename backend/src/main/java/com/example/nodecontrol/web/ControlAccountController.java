package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.ControlPlaneModels.ControlUserView;
import com.example.nodecontrol.dto.ControlPlaneModels.CreateControlUserRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.UpdateControlUserRequest;
import com.example.nodecontrol.security.ControlSessionService;
import com.example.nodecontrol.service.ControlAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/control/accounts")
public class ControlAccountController {

    private final ControlAccountService accountService;
    private final ControlSessionService sessionService;

    public ControlAccountController(ControlAccountService accountService,
                                    ControlSessionService sessionService) {
        this.accountService = accountService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ResponseEntity<List<ControlUserView>> list(HttpServletRequest request) {
        UUID currentUserId = currentUserId(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(accountService.list(currentUserId));
    }

    @PostMapping
    public ResponseEntity<ControlUserView> create(@Valid @RequestBody CreateControlUserRequest request,
                                                  HttpServletRequest servletRequest) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(accountService.create(request, currentUserId(servletRequest)));
    }

    @PatchMapping("/{userId}")
    public ResponseEntity<ControlUserView> update(@PathVariable UUID userId,
                                                  @Valid @RequestBody UpdateControlUserRequest request,
                                                  HttpServletRequest servletRequest) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(accountService.update(userId, request, currentUserId(servletRequest)));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> delete(@PathVariable UUID userId, HttpServletRequest servletRequest) {
        accountService.delete(userId, currentUserId(servletRequest));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private UUID currentUserId(HttpServletRequest request) {
        return sessionService.authenticatedSession(request)
                .orElseThrow(() -> new IllegalStateException("用户管理需要账号密码会话登录"))
                .userId();
    }
}
