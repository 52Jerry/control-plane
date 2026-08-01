package com.example.nodecontrol.web;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.dto.ControlPlaneModels.AgentRegistrationRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.AgentRegistrationResponse;
import com.example.nodecontrol.service.ManagedNodeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    public AgentRegistrationController(ManagedNodeService nodeService,
                                       ControlPlaneProperties properties) {
        this.nodeService = nodeService;
        this.properties = properties;
    }

    @PostMapping("/register")
    public AgentRegistrationResponse register(
            @RequestHeader(value = "X-Registration-Token", required = false) String suppliedToken,
            @Valid @RequestBody AgentRegistrationRequest request
    ) {
        String expectedToken = properties.getSecurity().getRegistrationToken();
        if (!StringUtils.hasText(expectedToken)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "节点自动注册尚未启用");
        }
        if (suppliedToken == null || !MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "节点注册令牌无效");
        }
        return nodeService.registerAgent(request);
    }
}
