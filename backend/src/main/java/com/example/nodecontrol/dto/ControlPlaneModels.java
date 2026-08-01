package com.example.nodecontrol.dto;

import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ControlPlaneModels {

    private ControlPlaneModels() {
    }

    public record MetaResponse(
            String version,
            boolean authRequired,
            boolean passwordLoginEnabled
    ) {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 1024) String password
    ) {
    }

    public record SessionResponse(boolean authenticated, String username) {
    }

    public record CreateControlUserRequest(
            @NotBlank @Size(min = 3, max = 64)
            @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,
            @NotBlank @Size(min = 10, max = 128) String password
    ) {
    }

    public record UpdateControlUserRequest(
            Boolean enabled,
            @Size(min = 10, max = 128) String password
    ) {
    }

    public record ControlUserView(
            UUID id,
            String username,
            boolean enabled,
            boolean current,
            Instant createdAt,
            Instant updatedAt,
            Instant lastLoginAt
    ) {
    }

    public record RegisterNodeRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 1024) String token,
            @Min(1) @Max(100000) Integer maxUsers
    ) {
        public RegisterNodeRequest(String name, String baseUrl, String token) {
            this(name, baseUrl, token, null);
        }
    }

    public record AgentRegistrationRequest(
            @NotBlank @Size(max = 128) String nodeId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 1024) String apiToken,
            @Size(max = 255) String host,
            @Size(max = 64) String managerVersion,
            @Min(1) @Max(100000) Integer maxUsers
    ) {
    }

    public record AgentRegistrationResponse(
            UUID id,
            String remoteNodeId,
            String status,
            boolean created
    ) {
    }

    public record UpdateNodeRequest(
            Boolean enabled,
            Boolean maintenance,
            @Min(1) @Max(100000) Integer maxUsers
    ) {
    }

    public record NodeView(
            UUID id,
            String name,
            String baseUrl,
            String remoteNodeId,
            String status,
            String host,
            String managerVersion,
            String singboxVersion,
            String singbox,
            boolean apiAvailable,
            double cpu,
            double memory,
            int connections,
            int systemConnections,
            int userCount,
            long upload,
            long download,
            long totalTraffic,
            Instant reportedAt,
            Instant lastCheckedAt,
            Instant lastSuccessfulHeartbeatAt,
            String lastError,
            int consecutiveFailures,
            boolean enabled,
            boolean maintenance,
            int maxUsers,
            Instant createdAt
    ) {
    }

    public record DashboardView(
            long nodeCount,
            long onlineNodeCount,
            long degradedNodeCount,
            long userCount,
            long connections,
            long upload,
            long download,
            long totalTraffic,
            long activeAllocationCount,
            long retryableAllocationCount
    ) {
    }

    public record ProvisionRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String userId,
            @NotEmpty List<@Pattern(regexp = "vless|vmess|socks") String> protocols,
            UUID preferredNodeId
    ) {
    }

    public record ProxyProvisionRequest(
            @NotBlank @Size(max = 50000) String input,
            @NotEmpty List<@Pattern(regexp = "vless|vmess|socks") String> protocols,
            UUID preferredNodeId,
            @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9._-]*$") String userPrefix
    ) {
    }

    public record ProxyProvisionResult(
            int rowNumber,
            String sourceAddress,
            Integer sourcePort,
            AllocationView allocation,
            String error
    ) {
    }

    public record ProxyProvisionBatchResponse(
            int total,
            int succeeded,
            int failed,
            List<ProxyProvisionResult> results
    ) {
    }

    public record AllocationView(
            UUID id,
            String requestKey,
            String userId,
            List<String> protocols,
            String state,
            UUID nodeId,
            String nodeName,
            String nodeHost,
            UserConnection connection,
            String lastError,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            String provisioningMode,
            boolean proxyBound,
            String proxyServer,
            Integer proxyPort
    ) {
        public AllocationView(UUID id,
                              String requestKey,
                              String userId,
                              List<String> protocols,
                              String state,
                              UUID nodeId,
                              String nodeName,
                              String nodeHost,
                              UserConnection connection,
                              String lastError,
                              Instant createdAt,
                              Instant updatedAt,
                              Instant completedAt) {
            this(id, requestKey, userId, protocols, state, nodeId, nodeName, nodeHost,
                    connection, lastError, createdAt, updatedAt, completedAt,
                    "DIRECT", false, null, null);
        }
    }
}
