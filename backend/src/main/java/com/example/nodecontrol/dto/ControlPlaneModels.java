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
            @NotBlank(message = "账号不能为空")
            @Size(max = 128, message = "账号不能超过 128 个字符") String username,
            @NotBlank(message = "密码不能为空")
            @Size(max = 1024, message = "密码不能超过 1024 个字符") String password
    ) {
    }

    public record SessionResponse(boolean authenticated, String username) {
    }

    public record CreateControlUserRequest(
            @NotBlank(message = "账号不能为空")
            @Size(min = 3, max = 64, message = "账号长度必须为 3-64 位")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "账号只能包含字母、数字、点、下划线和短横线") String username,
            @NotBlank(message = "密码不能为空")
            @Size(min = 10, max = 128, message = "密码长度必须为 10-128 位") String password
    ) {
    }

    public record UpdateControlUserRequest(
            Boolean enabled,
            @Size(min = 10, max = 128, message = "密码长度必须为 10-128 位") String password
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
            @NotBlank(message = "节点名称不能为空")
            @Size(max = 120, message = "节点名称不能超过 120 个字符") String name,
            @NotBlank(message = "API 地址不能为空")
            @Size(max = 500, message = "API 地址不能超过 500 个字符") String baseUrl,
            @NotBlank(message = "访问令牌不能为空")
            @Size(max = 1024, message = "访问令牌不能超过 1024 个字符") String token,
            @Min(value = 1, message = "最大用户数不能小于 1")
            @Max(value = 100000, message = "最大用户数不能超过 100000") Integer maxUsers
    ) {
        public RegisterNodeRequest(String name, String baseUrl, String token) {
            this(name, baseUrl, token, null);
        }
    }

    public record AgentRegistrationRequest(
            @NotBlank(message = "节点标识不能为空")
            @Size(max = 128, message = "节点标识不能超过 128 个字符") String nodeId,
            @NotBlank(message = "节点名称不能为空")
            @Size(max = 120, message = "节点名称不能超过 120 个字符") String name,
            @NotBlank(message = "API 地址不能为空")
            @Size(max = 500, message = "API 地址不能超过 500 个字符") String baseUrl,
            @NotBlank(message = "访问令牌不能为空")
            @Size(max = 1024, message = "访问令牌不能超过 1024 个字符") String apiToken,
            @Size(max = 255, message = "主机信息不能超过 255 个字符") String host,
            @Size(max = 64, message = "节点管理器版本不能超过 64 个字符") String managerVersion,
            @Min(value = 1, message = "最大用户数不能小于 1")
            @Max(value = 100000, message = "最大用户数不能超过 100000") Integer maxUsers
    ) {
    }

    public record AgentRegistrationResponse(
            UUID id,
            String remoteNodeId,
            String status,
            boolean created
    ) {
    }

    public record NodeInstallCommandResponse(
            String command,
            Instant expiresAt,
            long expiresInSeconds
    ) {
    }

    public record UpdateNodeRequest(
            Boolean enabled,
            Boolean maintenance,
            @Min(value = 1, message = "最大用户数不能小于 1")
            @Max(value = 100000, message = "最大用户数不能超过 100000") Integer maxUsers
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
            Integer socksInboundPort,
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
            @Size(max = 64, message = "用户 ID 不能超过 64 个字符")
            @Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "用户 ID 只能包含字母、数字、点、下划线和短横线") String userId,
            @NotEmpty(message = "至少选择一种协议")
            List<@Pattern(regexp = "vless|vmess|socks", message = "协议只支持 VLESS、VMess 或 SOCKS") String> protocols,
            UUID preferredNodeId
    ) {
    }

    public record ProxyProvisionRequest(
            @NotBlank(message = "请输入 SOCKS 节点信息")
            @Size(max = 50000, message = "SOCKS 节点信息不能超过 50000 个字符") String input,
            List<String> protocols,
            UUID preferredNodeId
    ) {
        /**
         * Compatibility constructor for clients compiled against the previous
         * prefix-based request contract. The prefix is intentionally ignored.
         */
        public ProxyProvisionRequest(String input, List<String> protocols,
                                      UUID preferredNodeId, String ignoredUserPrefix) {
            this(input, protocols, preferredNodeId);
        }
    }

    public record ProxyProvisionResult(
            int rowNumber,
            String sourceIp,
            String sourceDomain,
            String sourceAddress,
            Integer sourcePort,
            String countryName,
            String countryCode,
            String socksLink,
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
            Integer proxyPort,
            String proxyUsername,
            String proxyPassword,
            String sourceIp,
            String sourceAddress,
            Integer sourcePort
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
                    "DIRECT", false, null, null, null, null, null, null, null);
        }

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
                              Instant completedAt,
                              String provisioningMode,
                              boolean proxyBound,
                              String proxyServer,
                              Integer proxyPort) {
            this(id, requestKey, userId, protocols, state, nodeId, nodeName, nodeHost,
                    connection, lastError, createdAt, updatedAt, completedAt,
                    provisioningMode, proxyBound, proxyServer, proxyPort, null, null, null, null, null);
        }
    }
}
