package com.example.nodecontrol.web;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.dto.ControlPlaneModels.MetaResponse;
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

    public SystemController(ControlPlaneProperties properties, Optional<BuildProperties> buildProperties) {
        this.properties = properties;
        this.buildProperties = buildProperties;
    }

    @GetMapping("/meta")
    public MetaResponse meta() {
        return new MetaResponse(
                buildProperties.map(BuildProperties::getVersion).orElse("0.1.0"),
                StringUtils.hasText(properties.getSecurity().getAdminToken())
        );
    }
}

