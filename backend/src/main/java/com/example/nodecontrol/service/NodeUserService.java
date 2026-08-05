package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.client.RemoteNodeException;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ResidentialAllocation;
import com.example.nodecontrol.domain.ResidentialAllocationRepository;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class NodeUserService {

    private final ManagedNodeService nodeService;
    private final NodeManagerClient client;
    private final RemoteOperationService operationService;
    private final ResidentialAllocationRepository allocationRepository;
    private final ProvisioningService provisioningService;

    private static final List<String> ACTIVE_ALLOCATION_STATES =
            List.of("PROVISIONING", "RETRYABLE", "ACTIVE");

    public NodeUserService(ManagedNodeService nodeService,
                           NodeManagerClient client,
                           RemoteOperationService operationService,
                           ResidentialAllocationRepository allocationRepository,
                           ProvisioningService provisioningService) {
        this.nodeService = nodeService;
        this.client = client;
        this.operationService = operationService;
        this.allocationRepository = allocationRepository;
        this.provisioningService = provisioningService;
    }

    public UserPage listUsers(UUID nodeId, int page, int pageSize, String keyword) {
        return client.getUsers(nodeService.getNode(nodeId), page, pageSize, keyword);
    }

    public CreateUserResponse createUser(UUID nodeId, CreateUserRequest request, String idempotencyKey) {
        ManagedNode node = nodeService.getNode(nodeId);
        provisioningService.ensureUserIdAvailableOnNode(node, request.userId());
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
        try {
            OperationResponse response = operationService.execute(
                    node,
                    key,
                    "DELETE_USER",
                    Map.of("userId", userId),
                    OperationResponse.class,
                    () -> client.deleteUser(node, userId, key));
            if (response != null) {
                if (response.success()) {
                    releaseLocalAllocations(nodeId, userId);
                } else if (isRemoteUserMissing(200, response.message())) {
                    // Some older Node Manager versions return HTTP 200 with
                    // success=false when the user was already deleted. Treat
                    // that as an idempotent delete and release stale local
                    // allocation records.
                    releaseLocalAllocations(nodeId, userId);
                    return new OperationResponse(true, userId,
                            "远端节点用户已不存在，本地分配记录已释放");
                }
            }
            return response;
        } catch (RemoteNodeException exception) {
            // DELETE is intentionally idempotent: if the remote user was
            // already removed, the desired state has been reached. Release
            // only for an explicit missing-user response; a timeout, 5xx, or
            // unrelated conflict must remain visible to the caller.
            if (isRemoteUserMissing(exception)) {
                releaseLocalAllocations(nodeId, userId);
                return new OperationResponse(true, userId, "远端节点用户已不存在，本地分配记录已释放");
            }
            throw exception;
        }
    }

    private boolean isRemoteUserMissing(RemoteNodeException exception) {
        return isRemoteUserMissing(exception.getStatusCode(), exception.getMessage());
    }

    private boolean isRemoteUserMissing(int statusCode, String message) {
        if (statusCode == 404) {
            return true;
        }
        if ((statusCode != 409 && statusCode != 200) || message == null) {
            return false;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("user not found")
                || normalized.contains("user does not exist")
                || normalized.contains("no such user")
                || normalized.contains("user already deleted")
                || normalized.contains("\u7528\u6237\u4e0d\u5b58\u5728")
                || normalized.contains("\u7528\u6237\u672a\u627e\u5230");
    }

    private void releaseLocalAllocations(UUID nodeId, String userId) {
        List<ResidentialAllocation> allocations =
                allocationRepository.findAllByNodeIdAndControlUserIdAndStateIn(
                        nodeId, userId, ACTIVE_ALLOCATION_STATES);
        allocations.forEach(allocation ->
                allocation.fail("远端节点用户已删除，已释放本地分配记录", true));
        if (!allocations.isEmpty()) {
            allocationRepository.saveAll(allocations);
        }
    }

    public ReloadResponse reload(UUID nodeId) {
        ManagedNode node = nodeService.getNode(nodeId);
        ReloadResponse response = client.reload(node);
        nodeService.refresh(nodeId);
        return response;
    }
}
