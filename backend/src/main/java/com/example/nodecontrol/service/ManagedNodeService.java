package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ManagedNodeRepository;
import com.example.nodecontrol.domain.ResidentialAllocation;
import com.example.nodecontrol.domain.ResidentialAllocationRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.AgentRegistrationRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.AgentRegistrationResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.DashboardView;
import com.example.nodecontrol.dto.ControlPlaneModels.NodeTokenResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.NodeView;
import com.example.nodecontrol.dto.ControlPlaneModels.RegisterNodeRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.UpdateNodeRequest;
import com.example.nodecontrol.dto.RemoteModels.AgentHeartbeat;
import com.example.nodecontrol.dto.RemoteModels.AgentInfo;
import com.example.nodecontrol.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class ManagedNodeService {

    private static final Logger log = LoggerFactory.getLogger(ManagedNodeService.class);
    private static final List<String> NODE_BLOCKING_ALLOCATION_STATES = List.of("PROVISIONING", "RETRYABLE", "ACTIVE");

    private final ManagedNodeRepository repository;
    private final ResidentialAllocationRepository allocationRepository;
    private final NodeManagerClient client;
    private final ControlPlaneProperties properties;
    private final SecretCipher secretCipher;
    private final AuditLogService auditLogService;

    public ManagedNodeService(ManagedNodeRepository repository,
                              ResidentialAllocationRepository allocationRepository,
                              NodeManagerClient client,
                              ControlPlaneProperties properties,
                              SecretCipher secretCipher) {
        this(repository, allocationRepository, client, properties, secretCipher, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ManagedNodeService(ManagedNodeRepository repository,
                              ResidentialAllocationRepository allocationRepository,
                              NodeManagerClient client,
                              ControlPlaneProperties properties,
                              SecretCipher secretCipher,
                              AuditLogService auditLogService) {
        this.repository = repository;
        this.allocationRepository = allocationRepository;
        this.client = client;
        this.properties = properties;
        this.secretCipher = secretCipher;
        this.auditLogService = auditLogService;
    }

    public List<NodeView> listNodes() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(ManagedNode::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toView)
                .toList();
    }

    public DashboardView getDashboard() {
        List<NodeView> nodes = listNodes();
        return new DashboardView(
                nodes.size(),
                nodes.stream().filter(node -> "online".equals(node.status())).count(),
                nodes.stream().filter(node -> "degraded".equals(node.status())).count(),
                nodes.stream().mapToLong(NodeView::userCount).sum(),
                nodes.stream().mapToLong(NodeView::connections).sum(),
                nodes.stream().mapToLong(NodeView::upload).sum(),
                nodes.stream().mapToLong(NodeView::download).sum(),
                nodes.stream().mapToLong(NodeView::totalTraffic).sum(),
                allocationRepository.count((root, query, builder) -> builder.equal(root.get("state"), "ACTIVE")),
                allocationRepository.count((root, query, builder) -> builder.equal(root.get("state"), "RETRYABLE"))
        );
    }

    @Transactional
    public NodeView register(RegisterNodeRequest request) {
        return register(request, null);
    }

    @Transactional
    public NodeView register(RegisterNodeRequest request, UUID actorUserId) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String token = request.token().trim();
        repository.findByBaseUrl(baseUrl).ifPresent(node -> {
            throw new IllegalStateException("该节点地址已经注册");
        });

        AgentInfo info = client.getAgentInfo(baseUrl, token);
        repository.findByRemoteNodeId(info.nodeId()).ifPresent(node -> {
            throw new IllegalStateException("该节点管理器标识已经注册");
        });
        AgentHeartbeat heartbeat = client.getHeartbeat(new ManagedNode(request.name().trim(), baseUrl, token));
        ManagedNode node = new ManagedNode(request.name().trim(), baseUrl, secretCipher.encrypt(token));
        node.updateRegistration(
                request.name().trim(),
                baseUrl,
                secretCipher.encrypt(token),
                info,
                request.maxUsers() == null ? properties.getProvisioning().getDefaultMaxUsers() : request.maxUsers());
        if (request.maxUsers() != null) {
            node.setMaxUsers(request.maxUsers());
        }
        node.recordHeartbeat(heartbeat);
        NodeView view = toView(repository.save(node));
        audit("NODE_REGISTERED", actorUserId, node.getId(), "注册节点 " + node.getName());
        return view;
    }

    @Transactional
    public AgentRegistrationResponse registerAgent(AgentRegistrationRequest request) {
        return registerAgent(request, null);
    }

    @Transactional
    public AgentRegistrationResponse registerAgent(AgentRegistrationRequest request, UUID actorUserId) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String token = request.apiToken().trim();
        AgentInfo info = client.getAgentInfo(baseUrl, token);
        if (!request.nodeId().trim().equals(info.nodeId())) {
            throw new IllegalArgumentException("注册的节点标识与节点管理器返回值不一致");
        }
        AgentHeartbeat heartbeat = client.getHeartbeat(new ManagedNode(request.name().trim(), baseUrl, token));
        ManagedNode byRemoteId = repository.findByRemoteNodeId(info.nodeId()).orElse(null);
        ManagedNode byBaseUrl = repository.findByBaseUrl(baseUrl).orElse(null);
        if (byRemoteId != null && byBaseUrl != null && !byRemoteId.getId().equals(byBaseUrl.getId())) {
            throw new IllegalStateException("节点标识与 API 地址分别属于不同的已注册节点");
        }
        ManagedNode node = byRemoteId != null ? byRemoteId : byBaseUrl;
        boolean created = node == null;
        if (created) {
            node = new ManagedNode(request.name().trim(), baseUrl, secretCipher.encrypt(token));
        }
        int maxUsers = request.maxUsers() == null
                ? properties.getProvisioning().getDefaultMaxUsers()
                : request.maxUsers();
        node.updateRegistration(request.name().trim(), baseUrl, secretCipher.encrypt(token), info, maxUsers);
        if (request.maxUsers() != null || created) {
            node.setMaxUsers(maxUsers);
        }
        node.recordHeartbeat(heartbeat);
        node = repository.save(node);
        audit(created ? "NODE_REGISTERED" : "NODE_UPDATED", actorUserId, node.getId(),
                (created ? "自动注册节点 " : "更新节点注册信息 ") + node.getName());
        return new AgentRegistrationResponse(node.getId(), node.getRemoteNodeId(), node.getStatus(), created);
    }

    @Transactional
    public NodeView updateNode(UUID nodeId, UpdateNodeRequest request) {
        return updateNode(nodeId, request, null);
    }

    @Transactional
    public NodeView updateNode(UUID nodeId, UpdateNodeRequest request, UUID actorUserId) {
        ManagedNode node = getNode(nodeId);
        if (request.enabled() != null) {
            node.setEnabled(request.enabled());
        }
        if (request.maintenance() != null) {
            node.setMaintenance(request.maintenance());
        }
        if (request.maxUsers() != null) {
            node.setMaxUsers(request.maxUsers());
        }
        NodeView view = toView(repository.save(node));
        audit("NODE_UPDATED", actorUserId, nodeId, "更新节点配置");
        return view;
    }

    @Transactional
    public void deleteNode(UUID nodeId) {
        deleteNode(nodeId, null);
    }

    @Transactional
    public void deleteNode(UUID nodeId, UUID actorUserId) {
        ManagedNode node = getNode(nodeId);
        if (allocationRepository.countByNodeIdAndStateIn(nodeId, NODE_BLOCKING_ALLOCATION_STATES) > 0) {
            throw new IllegalStateException("节点仍有活动或待重试的自动开通记录，不能移除");
        }
        var historicalAllocations = allocationRepository.findAllByNodeId(nodeId);
        historicalAllocations.forEach(ResidentialAllocation::detachNode);
        allocationRepository.saveAll(historicalAllocations);
        repository.delete(node);
        audit("NODE_DELETED", actorUserId, nodeId, "删除节点 " + node.getName());
    }

    @Transactional
    public NodeView refresh(UUID nodeId) {
        return refresh(nodeId, null);
    }

    @Transactional
    public NodeView refresh(UUID nodeId, UUID actorUserId) {
        ManagedNode node = getNode(nodeId);
        refreshNode(node);
        NodeView view = toView(node);
        audit("NODE_REFRESHED", actorUserId, nodeId, "刷新节点状态");
        return view;
    }

    public ManagedNode getNode(UUID nodeId) {
        return repository.findById(nodeId)
                .orElseThrow(() -> new NoSuchElementException("节点不存在"));
    }

    public NodeTokenResponse getNodeToken(UUID nodeId) {
        ManagedNode node = getNode(nodeId);
        return new NodeTokenResponse(node.getId(), secretCipher.decrypt(node.getStoredApiToken()));
    }

    @Scheduled(fixedDelayString = "${control-plane.heartbeat.interval-ms:15000}")
    public void refreshAll() {
        if (!properties.getHeartbeat().isScheduledEnabled()) {
            return;
        }
        repository.findAll().forEach(node -> {
            try {
                refreshPersisted(node.getId());
            } catch (RuntimeException exception) {
                log.warn("节点 {} 的心跳状态保存失败: {}", node.getId(), exception.getMessage());
            }
        });
    }

    @Transactional
    public void refreshPersisted(UUID nodeId) {
        refreshNode(getNode(nodeId));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapNode() {
        ControlPlaneProperties.Bootstrap bootstrap = properties.getBootstrap();
        if (!bootstrap.isEnabled() || !StringUtils.hasText(bootstrap.getBaseUrl()) || !StringUtils.hasText(bootstrap.getToken())) {
            return;
        }
        String baseUrl = normalizeBaseUrl(bootstrap.getBaseUrl());
        if (repository.findByBaseUrl(baseUrl).isPresent()) {
            return;
        }
        try {
            register(new RegisterNodeRequest(bootstrap.getName(), baseUrl, bootstrap.getToken()));
            log.info("已自动注册节点管理器: {}", baseUrl);
        } catch (RuntimeException exception) {
            log.warn("节点管理器自动注册失败 {}: {}", baseUrl, exception.getMessage());
        }
    }

    private void refreshNode(ManagedNode node) {
        try {
            node.recordHeartbeat(client.getHeartbeat(node));
        } catch (RuntimeException exception) {
            ControlPlaneProperties.Heartbeat heartbeat = properties.getHeartbeat();
            node.recordHeartbeatFailure(
                    exception.getMessage(),
                    Math.max(1, heartbeat.getFailureThreshold()),
                    Instant.now().minus(Duration.ofMillis(Math.max(1, heartbeat.getOfflineAfterMs()))));
        }
        repository.save(node);
    }

    private NodeView toView(ManagedNode node) {
        return new NodeView(
                node.getId(),
                node.getName(),
                node.getBaseUrl(),
                node.getRemoteNodeId(),
                node.getStatus(),
                node.getHost(),
                node.getManagerVersion(),
                node.getSingboxVersion(),
                node.getSingbox(),
                node.isApiAvailable(),
                node.getCpu(),
                node.getMemory(),
                node.getConnections(),
                node.getSystemConnections(),
                node.getUserCount(),
                node.getSocksInboundPort(),
                node.getUpload(),
                node.getDownload(),
                node.getTotalTraffic(),
                node.getReportedAt(),
                node.getLastCheckedAt(),
                node.getLastSuccessfulHeartbeatAt(),
                node.getLastError(),
                node.getConsecutiveFailures(),
                node.isEnabled(),
                node.isMaintenance(),
                node.getMaxUsers(),
                node.getCreatedAt()
        );
    }

    private String normalizeBaseUrl(String rawUrl) {
        String value = rawUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("节点地址格式不正确");
        }
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("节点地址必须是有效的 HTTP 或 HTTPS 地址");
        }
        return value;
    }

    private void audit(String eventType, UUID actorUserId, UUID nodeId, String summary) {
        if (auditLogService != null) {
            auditLogService.record(eventType, actorUserId, "MANAGED_NODE",
                    nodeId == null ? null : nodeId.toString(), summary);
        }
    }
}
