package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.dto.RemoteModels.BindProxyRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.OperationResponse;
import com.example.nodecontrol.dto.RemoteModels.ReloadResponse;
import com.example.nodecontrol.dto.RemoteModels.TrafficResponse;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.UserPage;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class NodeUserService {

    private final ManagedNodeService nodeService;
    private final NodeManagerClient client;

    public NodeUserService(ManagedNodeService nodeService, NodeManagerClient client) {
        this.nodeService = nodeService;
        this.client = client;
    }

    public UserPage listUsers(UUID nodeId, int page, int pageSize, String keyword) {
        return client.getUsers(nodeService.getNode(nodeId), page, pageSize, keyword);
    }

    public CreateUserResponse createUser(UUID nodeId, CreateUserRequest request) {
        return client.createUser(nodeService.getNode(nodeId), request);
    }

    public UserConnection getConnections(UUID nodeId, String userId) {
        return client.getConnections(nodeService.getNode(nodeId), userId);
    }

    public TrafficResponse getTraffic(UUID nodeId, String userId) {
        return client.getTraffic(nodeService.getNode(nodeId), userId);
    }

    public OperationResponse bindProxy(UUID nodeId, BindProxyRequest request) {
        return client.bindProxy(nodeService.getNode(nodeId), request);
    }

    public OperationResponse deleteUser(UUID nodeId, String userId) {
        return client.deleteUser(nodeService.getNode(nodeId), userId);
    }

    public ReloadResponse reload(UUID nodeId) {
        ManagedNode node = nodeService.getNode(nodeId);
        ReloadResponse response = client.reload(node);
        nodeService.refresh(nodeId);
        return response;
    }
}

