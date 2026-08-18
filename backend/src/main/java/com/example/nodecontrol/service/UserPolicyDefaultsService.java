package com.example.nodecontrol.service;

import com.example.nodecontrol.domain.ControlPlaneSettings;
import com.example.nodecontrol.domain.ControlPlaneSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPolicyDefaultsService {

    private final ControlPlaneSettingsRepository repository;

    public UserPolicyDefaultsService(ControlPlaneSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DefaultUserPolicy getDefaults() {
        ControlPlaneSettings settings = repository.findById(ControlPlaneSettings.SINGLETON_ID)
                .orElseGet(() -> repository.save(new ControlPlaneSettings(
                        ControlPlaneSettings.INITIAL_TRAFFIC_LIMIT_BYTES,
                        ControlPlaneSettings.INITIAL_MAX_SOURCE_IPS)));
        return new DefaultUserPolicy(
                settings.getDefaultTrafficLimitBytes(), settings.getDefaultMaxSourceIps());
    }

    public record DefaultUserPolicy(long trafficLimitBytes, int maxSourceIps) {
    }
}
