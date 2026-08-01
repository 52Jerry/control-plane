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
        String expected = properties.getSecurity().getAdminToken();
        String supplied = request.getHeader("X-Control-Token");
        boolean validToken = StringUtils.hasText(expected) && supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
        if (!validToken && !sessionService.hasValidSession(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"message\":\"登录状态已失效，请重新登录\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}

