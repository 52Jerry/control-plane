package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.dto.RemoteModels.BindProxyRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.OperationResponse;
import com.example.nodecontrol.dto.RemoteModels.ProxyDetails;
import com.example.nodecontrol.dto.RemoteModels.ReloadResponse;
import com.example.nodecontrol.dto.RemoteModels.TrafficResponse;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.UserPage;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class NodeUserService {

    private final ManagedNodeService nodeService;
    private final NodeManagerClient client;
    private final RemoteOperationService operationService;

    public NodeUserService(ManagedNodeService nodeService,
                           NodeManagerClient client,
                           RemoteOperationService operationService) {
        this.nodeService = nodeService;
        this.client = client;
        this.operationService = operationService;
    }

    public UserPage listUsers(UUID nodeId, int page, int pageSize, String keyword) {
        return client.getUsers(nodeService.getNode(nodeId), page, pageSize, keyword);
    }

    public CreateUserResponse createUser(UUID nodeId, CreateUserRequest request, String idempotencyKey) {
        ManagedNode node = nodeService.getNode(nodeId);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey.trim();
        return operationService.execute(node, key, "CREATE_USER", request, CreateUserResponse.class,
                () -> client.createUser(node, request, key));
    }

    public UserConnection getConnections(UUID nodeId, String userId) {
        return client.getConnections(nodeService.getNode(nodeId), userId);
    }

    public TrafficResponse getTraffic(UUID nodeId, String userId) {
        return client.getTraffic(nodeService.getNode(nodeId), userId);
    }

    public ProxyDetails getProxy(UUID nodeId, String userId) {
        return client.getProxy(nodeService.getNode(nodeId), userId);
    }

    public OperationResponse bindProxy(UUID nodeId, BindProxyRequest request, String idempotencyKey) {
        ManagedNode node = nodeService.getNode(nodeId);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey.trim();
        return operationService.execute(node, key, "BIND_PROXY", request, OperationResponse.class,
                () -> client.bindProxy(node, request, key));
    }

    public OperationResponse deleteUser(UUID nodeId, String userId, String idempotencyKey) {
        ManagedNode node = nodeService.getNode(nodeId);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey.trim();
        return operationService.execute(node, key, "DELETE_USER", Map.of("userId", userId), OperationResponse.class,
                () -> client.deleteUser(node, userId, key));
    }

    public ReloadResponse reload(UUID nodeId) {
        ManagedNode node = nodeService.getNode(nodeId);
        ReloadResponse response = client.reload(node);
        nodeService.refresh(nodeId);
        return response;
    }
}
