package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.ControlPlaneModels.LoginRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.SessionResponse;
import com.example.nodecontrol.security.ControlSessionService;
import com.example.nodecontrol.service.ControlAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/control/auth")
public class AuthenticationController {

    private final ControlSessionService sessionService;
    private final ControlAccountService accountService;

    public AuthenticationController(ControlSessionService sessionService,
                                    ControlAccountService accountService) {
        this.sessionService = sessionService;
        this.accountService = accountService;
    }

    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletRequest servletRequest) {
        var user = accountService.authenticate(request.username(), request.password());
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .cacheControl(CacheControl.noStore())
                    .body(new SessionResponse(false, null));
        }
        String token = sessionService.createSessionToken(user.get());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE,
                        sessionService.sessionCookie(token, servletRequest.isSecure()).toString())
                .body(new SessionResponse(true, user.get().getUsername()));
    }

    @GetMapping("/session")
    public ResponseEntity<SessionResponse> session(HttpServletRequest request) {
        var session = sessionService.authenticatedSession(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SessionResponse(session.isPresent(), session.map(ControlSessionService.AuthenticatedSession::username).orElse(null)));
    }

    @PostMapping("/logout")
    public ResponseEntity<SessionResponse> logout(HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, sessionService.expiredCookie(request.isSecure()).toString())
                .body(new SessionResponse(false, null));
    }
}
