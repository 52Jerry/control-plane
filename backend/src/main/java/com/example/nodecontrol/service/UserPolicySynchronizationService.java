package com.example.nodecontrol.service;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ManagedNodeRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.UserPolicyMigrationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Periodically applies current policy defaults to legacy users with missing fields. */
@Service
public class UserPolicySynchronizationService {

    private static final Logger log = LoggerFactory.getLogger(UserPolicySynchronizationService.class);

    private final ManagedNodeRepository nodeRepository;
    private final NodeUserService nodeUserService;
    private final ControlPlaneProperties properties;

    public UserPolicySynchronizationService(ManagedNodeRepository nodeRepository,
                                             NodeUserService nodeUserService,
                                             ControlPlaneProperties properties) {
        this.nodeRepository = nodeRepository;
        this.nodeUserService = nodeUserService;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${control-plane.user-policy-sync.initial-delay-ms:30000}",
            fixedDelayString = "${control-plane.user-policy-sync.interval-ms:300000}")
    public void synchronize() {
        ControlPlaneProperties.UserPolicySync config = properties.getUserPolicySync();
        if (!config.isEnabled()) {
            return;
        }
        nodeRepository.findAll().stream()
                .filter(this::isAvailable)
                .forEach(node -> synchronizeNode(node, config));
    }

    private boolean isAvailable(ManagedNode node) {
        return node.isEnabled()
                && !node.isMaintenance()
                && node.isApiAvailable()
                && ("online".equals(node.getStatus()) || "degraded".equals(node.getStatus()));
    }

    private void synchronizeNode(ManagedNode node, ControlPlaneProperties.UserPolicySync config) {
        try {
            UserPolicyMigrationResponse result = nodeUserService.synchronizeMissingPolicies(node.getId());
            if (result.failed() > 0) {
                log.warn("节点 {} 的历史用户策略回填部分失败: 成功 {}, 失败 {}",
                        node.getName(), result.succeeded(), result.failed());
            } else if (result.succeeded() > 0) {
                log.info("节点 {} 的历史用户策略回填完成: {} 个用户",
                        node.getName(), result.succeeded());
            }
        } catch (RuntimeException exception) {
            log.warn("节点 {} 的历史用户策略回填失败: {}", node.getName(), exception.getMessage());
        }
    }
}
