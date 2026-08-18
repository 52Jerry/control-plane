package com.example.nodecontrol.dto;

import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.NodeAccessInfo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public record SessionResponse(boolean authenticated, String username, String role) {
        public SessionResponse(boolean authenticated, String username) {
            this(authenticated, username, null);
        }
    }

    public record CreateControlUserRequest(
            @NotBlank(message = "账号不能为空")
            @Size(min = 3, max = 64, message = "账号长度必须为 3-64 位")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "账号只能包含字母、数字、点、下划线和短横线") String username,
            @NotBlank(message = "密码不能为空")
            @Size(min = 10, max = 128, message = "密码长度必须为 10-128 位") String password,
            @Pattern(regexp = "ADMIN|NODE_OPS|PROVISIONER|READONLY", message = "账号角色不受支持") String role
    ) {
        public CreateControlUserRequest(String username, String password) {
            this(username, password, "ADMIN");
        }
    }

    public record UpdateControlUserRequest(
            Boolean enabled,
            @Size(min = 10, max = 128, message = "密码长度必须为 10-128 位") String password,
            @Pattern(regexp = "ADMIN|NODE_OPS|PROVISIONER|READONLY", message = "账号角色不受支持") String role
    ) {
        public UpdateControlUserRequest(Boolean enabled, String password) {
            this(enabled, password, null);
        }
    }

    public record UpdateControlUserRoleRequest(
            @NotBlank(message = "角色不能为空")
            @Pattern(regexp = "ADMIN|NODE_OPS|PROVISIONER|READONLY", message = "账号角色不受支持") String role
    ) {
    }

    public record ControlUserView(
            UUID id,
            String username,
            boolean enabled,
            boolean current,
            String role,
            Instant createdAt,
            Instant updatedAt,
            Instant lastLoginAt
    ) {
    }

    public record AuditLogView(
            UUID id,
            String eventType,
            UUID actorUserId,
            String actorUsername,
            String targetType,
            String targetId,
            String summary,
            Instant createdAt
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

    public record BatchConnectionsRequest(
            @NotEmpty(message = "用户 ID 不能为空")
            @Size(max = 100, message = "单次最多读取 100 个用户连接")
            List<@NotBlank(message = "用户 ID 不能为空")
                    @Size(max = 64, message = "用户 ID 不能超过 64 个字符") String> userIds
    ) {
    }

    public record BatchConnectionResult(
            String userId,
            UserConnection connection,
            String error
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

    public record NodeTokenResponse(
            UUID nodeId,
            String token
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
            UUID preferredNodeId,
            @Min(value = 0, message = "流量额度不能小于 0") Long trafficLimitBytes,
            @Min(value = 0, message = "最大来源 IP 数不能小于 0")
            @Max(value = 1000, message = "最大来源 IP 数不能超过 1000") Integer maxSourceIps
    ) {
        public ProvisionRequest(String userId, List<String> protocols, UUID preferredNodeId) {
            this(userId, protocols, preferredNodeId, null, null);
        }
    }

    public record ProxyProvisionRequest(
            @NotBlank(message = "请输入 SOCKS 节点信息")
            @Size(max = 50000, message = "SOCKS 节点信息不能超过 50000 个字符") String input,
            List<String> protocols,
            UUID preferredNodeId
    ) {
        /** Compatibility constructor for clients compiled against the previous prefix-based contract. */
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

    public record UserPolicyMigrationFailure(
            String userId,
            String message
    ) {
    }

    public record UserPolicyMigrationResponse(
            UUID nodeId,
            String nodeName,
            int total,
            int succeeded,
            int failed,
            long trafficLimitBytes,
            int maxSourceIps,
            List<UserPolicyMigrationFailure> failures
    ) {
    }

    public record DefaultUserPolicyResponse(
            long trafficLimitBytes,
            int maxSourceIps
    ) {
    }

    public record ProxyProvisionBatchResponse(
            int total,
            int succeeded,
            int failed,
            List<ProxyProvisionResult> results
    ) {
    }

    /** Paged allocation list; the controller preserves the legacy array response when paging is omitted. */
    public record AllocationPageResponse(
            List<AllocationView> items,
            int page,
            int pageSize,
            long total,
            int totalPages
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
            Map<String, String> protocolsAll,
            Map<String, Object> protocolInfo,
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
            Integer sourcePort,
            NodeAccessInfo access
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
                    connection, Map.of(), Map.of(), lastError, createdAt, updatedAt, completedAt,
                    "DIRECT", false, null, null, null, null, null, null, null, null);
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
                    connection, Map.of(), Map.of(), lastError, createdAt, updatedAt, completedAt,
                    provisioningMode, proxyBound, proxyServer, proxyPort, null, null, null, null, null, null);
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
                              Map<String, String> protocolsAll,
                              Map<String, Object> protocolInfo,
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
                              Integer sourcePort) {
            this(id, requestKey, userId, protocols, state, nodeId, nodeName, nodeHost,
                    connection, protocolsAll, protocolInfo, lastError, createdAt, updatedAt, completedAt,
                    provisioningMode, proxyBound, proxyServer, proxyPort, proxyUsername, proxyPassword,
                    sourceIp, sourceAddress, sourcePort, null);
        }

        public AllocationView {
            protocolsAll = protocolsAll == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(protocolsAll));
            protocolInfo = protocolInfo == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(protocolInfo));
        }
    }
}
