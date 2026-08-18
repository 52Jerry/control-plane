package com.example.nodecontrol.web;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.dto.ControlPlaneModels.MetaResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.DefaultUserPolicyResponse;
import com.example.nodecontrol.security.ControlSessionService;
import com.example.nodecontrol.service.UserPolicyDefaultsService;
import org.springframework.boot.info.BuildProperties;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/control")
public class SystemController {

    private final ControlPlaneProperties properties;
    private final Optional<BuildProperties> buildProperties;
    private final ControlSessionService sessionService;
    private final UserPolicyDefaultsService policyDefaultsService;

    public SystemController(ControlPlaneProperties properties,
                            Optional<BuildProperties> buildProperties,
                            ControlSessionService sessionService,
                            UserPolicyDefaultsService policyDefaultsService) {
        this.properties = properties;
        this.buildProperties = buildProperties;
        this.sessionService = sessionService;
        this.policyDefaultsService = policyDefaultsService;
    }

    @GetMapping("/meta")
    public MetaResponse meta() {
        return new MetaResponse(
                buildProperties.map(BuildProperties::getVersion).orElse("0.1.0"),
                StringUtils.hasText(properties.getSecurity().getAdminToken())
                        || sessionService.isPasswordLoginEnabled(),
                sessionService.isPasswordLoginEnabled()
        );
    }

    @GetMapping("/settings/default-user-policy")
    public DefaultUserPolicyResponse defaultUserPolicy() {
        UserPolicyDefaultsService.DefaultUserPolicy defaults = policyDefaultsService.getDefaults();
        return new DefaultUserPolicyResponse(defaults.trafficLimitBytes(), defaults.maxSourceIps());
    }
}

