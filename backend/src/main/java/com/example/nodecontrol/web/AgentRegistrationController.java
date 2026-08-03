package com.example.nodecontrol.web;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.dto.ControlPlaneModels.AgentRegistrationRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.AgentRegistrationResponse;
import com.example.nodecontrol.service.ManagedNodeService;
import com.example.nodecontrol.service.NodeInstallationService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/control/agent")
public class AgentRegistrationController {

    private final ManagedNodeService nodeService;
    private final ControlPlaneProperties properties;
    private final NodeInstallationService installationService;

    public AgentRegistrationController(ManagedNodeService nodeService,
                                       ControlPlaneProperties properties,
                                       NodeInstallationService installationService) {
        this.nodeService = nodeService;
        this.properties = properties;
        this.installationService = installationService;
    }

    @PostMapping("/register")
    public ResponseEntity<AgentRegistrationResponse> register(
            @RequestHeader(value = "X-Registration-Token", required = false) String suppliedToken,
            @RequestHeader(value = "X-Install-Token", required = false) String installToken,
            @Valid @RequestBody AgentRegistrationRequest request
    ) {
        String expectedToken = properties.getSecurity().getRegistrationToken();
        boolean validRegistrationToken = StringUtils.hasText(expectedToken)
                && suppliedToken != null
                && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8));
        if (validRegistrationToken) {
            return noStore(nodeService.registerAgent(request));
        }

        if (!StringUtils.hasText(installToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "节点注册令牌或一次性安装码无效");
        }
        try (NodeInstallationService.RegistrationPermit permit = installationService.claim(installToken)) {
            AgentRegistrationResponse response = nodeService.registerAgent(request);
            permit.complete(response);
            return noStore(response);
        }
    }

    private ResponseEntity<AgentRegistrationResponse> noStore(AgentRegistrationResponse response) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
