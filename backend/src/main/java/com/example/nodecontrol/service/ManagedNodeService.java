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
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate transactionTemplate;

    public ManagedNodeService(ManagedNodeRepository repository,
                              ResidentialAllocationRepository allocationRepository,
                              NodeManagerClient client,
                              ControlPlaneProperties properties,
                              SecretCipher secretCipher) {
        this(repository, allocationRepository, client, properties, secretCipher, null, null);
    }

    public ManagedNodeService(ManagedNodeRepository repository,
                              ResidentialAllocationRepository allocationRepository,
                              NodeManagerClient client,
                              ControlPlaneProperties properties,
                              SecretCipher secretCipher,
                              AuditLogService auditLogService) {
        this(repository, allocationRepository, client, properties, secretCipher, auditLogService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ManagedNodeService(ManagedNodeRepository repository,
                              ResidentialAllocationRepository allocationRepository,
                              NodeManagerClient client,
                              ControlPlaneProperties properties,
                              SecretCipher secretCipher,
                              AuditLogService auditLogService,
                              TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.allocationRepository = allocationRepository;
        this.client = client;
        this.properties = properties;
        this.secretCipher = secretCipher;
        this.auditLogService = auditLogService;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional(readOnly = true)
    public List<NodeView> listNodes() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(ManagedNode::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public DashboardView getDashboard() {
        List<ManagedNode> nodes = repository.findAll();
        long activeCount = allocationRepository.countByState("ACTIVE");
        long retryableCount = allocationRepository.countByState("RETRYABLE");
        return new DashboardView(
                nodes.size(),
                nodes.stream().filter(node -> "online".equals(node.getStatus())).count(),
                nodes.stream().filter(node -> "degraded".equals(node.getStatus())).count(),
                nodes.stream().mapToLong(ManagedNode::getUserCount).sum(),
                nodes.stream().mapToLong(ManagedNode::getConnections).sum(),
                nodes.stream().mapToLong(ManagedNode::getUpload).sum(),
                nodes.stream().mapToLong(ManagedNode::getDownload).sum(),
                nodes.stream().mapToLong(ManagedNode::getTotalTraffic).sum(),
                activeCount,
                retryableCount
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
        AgentHeartbeat heartbeat = client.getHeartbeat(new ManagedNode(request.name().trim(), baseUrl, token));
        // 按服务器 IP 防重复注册：重装 Node Manager 后节点标识会变化，但 IP 不变。
        rejectDuplicateHost(heartbeat, () -> repository.findByRemoteNodeId(info.nodeId()).ifPresent(node -> {
            throw new IllegalStateException("该节点管理器标识已经注册");
        }));
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
            // 新注册时按服务器 IP 防重复；已匹配到现有节点则属于更新，不做 IP 校验。
            rejectDuplicateHost(heartbeat, null);
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

    /**
     * 按服务器 IP（心跳 host）拒绝重复注册。重装 Node Manager 会生成新的
     * 节点标识（主机名 + machine-id 哈希），旧标识校验认不出同一台服务器，
     * IP 校验能兜住这种场景。心跳未返回 host 时回退到调用方提供的校验。
     */
    private void rejectDuplicateHost(AgentHeartbeat heartbeat, Runnable fallbackWhenHostUnknown) {
        String host = heartbeat == null || heartbeat.host() == null ? null : heartbeat.host().trim();
        if (host == null || host.isBlank()) {
            if (fallbackWhenHostUnknown != null) {
                fallbackWhenHostUnknown.run();
            }
            return;
        }
        List<ManagedNode> sameHost = repository.findByHost(host);
        if (!sameHost.isEmpty()) {
            throw new IllegalStateException(
                    "该节点 IP " + host + " 已存在（现有节点：" + sameHost.getFirst().getName() + "）");
        }
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

    /**
     * 心跳 HTTP 请求必须在行锁之外执行：对离线节点的请求可能长时间挂起，
     * 若在 findByIdForUpdate 的事务内请求，会长时间持有 managed_nodes 行锁，
     * 阻塞批量开通的选节点 SELECT ... FOR UPDATE，最终触发
     * socketTimeout 掐断连接（"Unable to rollback against JDBC Connection"）。
     */
    public void refreshPersisted(UUID nodeId) {
        ManagedNode snapshot = repository.findById(nodeId)
                .orElseThrow(() -> new NoSuchElementException("节点不存在"));
        AgentHeartbeat heartbeat = null;
        RuntimeException failure = null;
        try {
            heartbeat = client.getHeartbeat(snapshot);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        AgentHeartbeat result = heartbeat;
        RuntimeException error = failure;
        transactionTemplate.executeWithoutResult(status -> {
            ManagedNode node = repository.findByIdForUpdate(nodeId)
                    .orElseThrow(() -> new NoSuchElementException("节点不存在"));
            if (result != null) {
                node.recordHeartbeat(result);
            } else {
                ControlPlaneProperties.Heartbeat config = properties.getHeartbeat();
                node.recordHeartbeatFailure(
                        error == null ? null : error.getMessage(),
                        Math.max(1, config.getFailureThreshold()),
                        Instant.now().minus(Duration.ofMillis(Math.max(1, config.getOfflineAfterMs()))));
            }
            repository.save(node);
        });
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
        if (auditLogService == null) {
            return;
        }
        try {
            auditLogService.record(eventType, actorUserId, "MANAGED_NODE",
                    nodeId == null ? null : nodeId.toString(), summary);
        } catch (RuntimeException exception) {
            log.warn("审计日志记录失败 ({}): {}", eventType, exception.getMessage());
        }
    }
}
