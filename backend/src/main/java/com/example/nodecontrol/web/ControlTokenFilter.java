package com.example.nodecontrol.web;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.security.ControlSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Component
public class ControlTokenFilter extends OncePerRequestFilter {

    private final ControlPlaneProperties properties;
    private final ControlSessionService sessionService;

    public ControlTokenFilter(ControlPlaneProperties properties, ControlSessionService sessionService) {
        this.properties = properties;
        this.sessionService = sessionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/control")
                || "/api/control/meta".equals(path)
                || "/api/control/agent/register".equals(path)
                || path.startsWith("/api/control/auth/")
                || (!StringUtils.hasText(properties.getSecurity().getAdminToken())
                    && !sessionService.isPasswordLoginEnabled());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String expected = properties.getSecurity().getAdminToken();
        String supplied = request.getHeader("X-Control-Token");
        boolean validToken = StringUtils.hasText(expected) && supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
        var session = sessionService.authenticatedSession(request);
        if (!validToken && session.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录状态已失效，请重新登录");
            return;
        }
        String role = validToken ? "ADMIN" : session.orElseThrow().role();
        request.setAttribute("control.role", role);
        request.setAttribute("control.actorUserId", validToken ? null : session.orElseThrow().userId());
        if (!allowed(role, request.getMethod(), request.getRequestURI())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "当前账号没有执行此操作的权限");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean allowed(String role, String method, String path) {
        if ("ADMIN".equals(role)) return true;
        if (path.startsWith("/api/control/accounts") || path.startsWith("/api/control/audit-logs")) {
            return false;
        }
        if (path.startsWith("/api/control/node-installation")) {
            return "NODE_OPS".equals(role);
        }
        boolean write = Set.of("POST", "PATCH", "PUT", "DELETE").contains(method.toUpperCase());
        if (!write && "READONLY".equals(role)
                && (path.matches(".*/users/[^/]+/(connections|proxy|traffic)$")
                || path.matches("/api/control/allocations/[^/]+$"))) {
            return false;
        }
        if (!write) return true;
        if (path.startsWith("/api/control/nodes")) {
            boolean userOperation = path.matches(".*/users(?:/.*)?$");
            if (userOperation) return "PROVISIONER".equals(role);
            return "NODE_OPS".equals(role);
        }
        if (path.startsWith("/api/control/allocations")) {
            return Set.of("NODE_OPS", "PROVISIONER").contains(role);
        }
        return false;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
