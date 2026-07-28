package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ManagedNodeRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.DashboardView;
import com.example.nodecontrol.dto.ControlPlaneModels.NodeView;
import com.example.nodecontrol.dto.ControlPlaneModels.RegisterNodeRequest;
import com.example.nodecontrol.dto.RemoteModels.AgentHeartbeat;
import com.example.nodecontrol.dto.RemoteModels.AgentInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ManagedNodeService {

    private static final Logger log = LoggerFactory.getLogger(ManagedNodeService.class);

    private final ManagedNodeRepository repository;
    private final NodeManagerClient client;
    private final ControlPlaneProperties properties;
    private final Map<UUID, NodeRuntimeState> runtimeStates = new ConcurrentHashMap<>();

    public ManagedNodeService(ManagedNodeRepository repository,
                              NodeManagerClient client,
                              ControlPlaneProperties properties) {
        this.repository = repository;
        this.client = client;
        this.properties = properties;
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
                nodes.stream().mapToLong(NodeView::totalTraffic).sum()
        );
    }

    @Transactional
    public NodeView register(RegisterNodeRequest request) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        repository.findByBaseUrl(baseUrl).ifPresent(node -> {
            throw new IllegalStateException("该节点地址已经注册");
        });

        AgentInfo info = client.getAgentInfo(baseUrl, request.token().trim());
        AgentHeartbeat heartbeat = client.getHeartbeat(new ManagedNode(request.name().trim(), baseUrl, request.token().trim()));
        ManagedNode node = repository.save(new ManagedNode(request.name().trim(), baseUrl, request.token().trim()));
        runtimeStates.put(node.getId(), NodeRuntimeState.online(info, heartbeat));
        return toView(node);
    }

    @Transactional
    public void deleteNode(UUID nodeId) {
        ManagedNode node = getNode(nodeId);
        repository.delete(node);
        runtimeStates.remove(nodeId);
    }

    public NodeView refresh(UUID nodeId) {
        ManagedNode node = getNode(nodeId);
        refreshNode(node);
        return toView(node);
    }

    public ManagedNode getNode(UUID nodeId) {
        return repository.findById(nodeId)
                .orElseThrow(() -> new NoSuchElementException("节点不存在"));
    }

    @Scheduled(fixedDelayString = "${control-plane.heartbeat.interval-ms:15000}")
    public void refreshAll() {
        repository.findAll().forEach(this::refreshNode);
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
            log.info("Bootstrapped Node Manager agent at {}", baseUrl);
        } catch (RuntimeException exception) {
            log.warn("Could not bootstrap Node Manager agent at {}: {}", baseUrl, exception.getMessage());
        }
    }

    private void refreshNode(ManagedNode node) {
        try {
            AgentHeartbeat heartbeat = client.getHeartbeat(node);
            runtimeStates.compute(node.getId(), (id, current) -> NodeRuntimeState.online(
                    current == null ? null : current.info(), heartbeat));
        } catch (RuntimeException exception) {
            runtimeStates.compute(node.getId(), (id, current) -> NodeRuntimeState.offline(current, exception.getMessage()));
        }
    }

    private NodeView toView(ManagedNode node) {
        NodeRuntimeState state = runtimeStates.get(node.getId());
        AgentHeartbeat heartbeat = state == null ? null : state.heartbeat();
        AgentInfo info = state == null ? null : state.info();
        return new NodeView(
                node.getId(),
                node.getName(),
                node.getBaseUrl(),
                heartbeat != null ? heartbeat.nodeId() : info != null ? info.nodeId() : null,
                state == null ? "unknown" : state.status(),
                heartbeat == null ? null : heartbeat.host(),
                heartbeat != null ? heartbeat.managerVersion() : info != null ? info.managerVersion() : null,
                heartbeat == null ? null : heartbeat.singboxVersion(),
                heartbeat == null ? null : heartbeat.singbox(),
                heartbeat != null && heartbeat.apiAvailable(),
                heartbeat == null ? 0 : heartbeat.cpu(),
                heartbeat == null ? 0 : heartbeat.memory(),
                heartbeat == null ? 0 : heartbeat.connections(),
                heartbeat == null ? 0 : heartbeat.systemConnections(),
                heartbeat == null ? 0 : heartbeat.userCount(),
                heartbeat == null || heartbeat.traffic() == null ? 0 : heartbeat.traffic().upload(),
                heartbeat == null || heartbeat.traffic() == null ? 0 : heartbeat.traffic().download(),
                heartbeat == null || heartbeat.traffic() == null ? 0 : heartbeat.traffic().total(),
                heartbeat == null ? null : heartbeat.reportedAt(),
                state == null ? null : state.lastCheckedAt(),
                state == null ? null : state.lastError(),
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

    private record NodeRuntimeState(
            String status,
            AgentInfo info,
            AgentHeartbeat heartbeat,
            Instant lastCheckedAt,
            String lastError
    ) {
        static NodeRuntimeState online(AgentInfo info, AgentHeartbeat heartbeat) {
            String status = heartbeat.status() == null ? "online" : heartbeat.status();
            return new NodeRuntimeState(status, info, heartbeat, Instant.now(), null);
        }

        static NodeRuntimeState offline(NodeRuntimeState current, String error) {
            return new NodeRuntimeState(
                    "offline",
                    current == null ? null : current.info,
                    current == null ? null : current.heartbeat,
                    Instant.now(),
                    error
            );
        }
    }
}

