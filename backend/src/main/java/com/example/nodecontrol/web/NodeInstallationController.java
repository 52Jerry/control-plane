package com.example.nodecontrol.web;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.dto.ControlPlaneModels.NodeInstallCommandResponse;
import com.example.nodecontrol.security.ControlSessionService;
import com.example.nodecontrol.service.NodeInstallationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

@RestController
@RequestMapping("/api/control/node-installation")
public class NodeInstallationController {

    private final NodeInstallationService installationService;
    private final ControlSessionService sessionService;
    private final ControlPlaneProperties properties;

    public NodeInstallationController(NodeInstallationService installationService,
                                      ControlSessionService sessionService,
                                      ControlPlaneProperties properties) {
        this.installationService = installationService;
        this.sessionService = sessionService;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<NodeInstallCommandResponse> create(HttpServletRequest request) {
        String createdBy = sessionService.authenticatedSession(request)
                .map(ControlSessionService.AuthenticatedSession::username)
                .orElse("control-api");
        UUID actorUserId = sessionService.authenticatedSession(request)
                .map(ControlSessionService.AuthenticatedSession::userId)
                .orElse(null);
        NodeInstallCommandResponse response = actorUserId == null
                ? installationService.issueCommand(createdBy, resolveControlPlaneUrl(request))
                : installationService.issueCommand(createdBy, resolveControlPlaneUrl(request), actorUserId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private String resolveControlPlaneUrl(HttpServletRequest request) {
        if (StringUtils.hasText(properties.getPublicUrl())) {
            return properties.getPublicUrl();
        }
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        try {
            return new URI(
                    scheme,
                    null,
                    request.getServerName(),
                    defaultPort ? -1 : port,
                    StringUtils.hasText(request.getContextPath()) ? request.getContextPath() : null,
                    null,
                    null).toString();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("无法根据当前请求确定控制中心公网地址，请配置 CONTROL_PLANE_PUBLIC_URL");
        }
    }
}
