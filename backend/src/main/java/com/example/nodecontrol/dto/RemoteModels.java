package com.example.nodecontrol.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public final class RemoteModels {

    private RemoteModels() {
    }

    public record AgentInfo(
            String agent,
            String apiVersion,
            String managerVersion,
            String nodeId,
            List<String> capabilities,
            List<String> controlPlaneResponsibilities,
            String idempotencyHeader,
            String heartbeatEndpoint
    ) {
    }

    public record TrafficTotals(
            long upload,
            long download,
            long total,
            boolean available,
            String source,
            Instant collectedAt
    ) {
    }

    public record AgentHeartbeat(
            String nodeId,
            String name,
            String host,
            String status,
            String managerVersion,
            String singboxVersion,
            String singbox,
            boolean apiAvailable,
            double cpu,
            double memory,
            int connections,
            int systemConnections,
            int userCount,
            Integer socksPort,
            TrafficTotals traffic,
            Instant reportedAt
    ) {
        /** Compatibility constructor for older tests/agents without socksPort. */
        public AgentHeartbeat(String nodeId,
                              String name,
                              String host,
                              String status,
                              String managerVersion,
                              String singboxVersion,
                              String singbox,
                              boolean apiAvailable,
                              double cpu,
                              double memory,
                              int connections,
                              int systemConnections,
                              int userCount,
                              TrafficTotals traffic,
                              Instant reportedAt) {
            this(nodeId, name, host, status, managerVersion, singboxVersion, singbox,
                    apiAvailable, cpu, memory, connections, systemConnections, userCount,
                    null, traffic, reportedAt);
        }
    }

    public record UserSummary(
            String userId,
            List<String> protocols,
            String socksUsername,
            boolean proxyBound,
            String proxyServer,
            long upload,
            long download,
            long total,
            Long trafficLimitBytes,
            Integer maxSourceIps,
            List<String> activeSourceIps,
            String status,
            Instant createdAt,
            NodeAccessInfo access
    ) {
        public UserSummary(String userId,
                           List<String> protocols,
                           String socksUsername,
                           boolean proxyBound,
                           String proxyServer,
                           long upload,
                           long download,
                           long total,
                           String status,
                           Instant createdAt) {
            this(userId, protocols, socksUsername, proxyBound, proxyServer,
                    upload, download, total, null, null, List.of(), status, createdAt, null);
        }

        public UserSummary(String userId,
                           List<String> protocols,
                           String socksUsername,
                           boolean proxyBound,
                           String proxyServer,
                           long upload,
                           long download,
                           long total,
                           String status,
                           Instant createdAt,
                           NodeAccessInfo access) {
            this(userId, protocols, socksUsername, proxyBound, proxyServer,
                    upload, download, total, null, null, List.of(), status, createdAt, access);
        }

        public UserSummary {
            activeSourceIps = activeSourceIps == null ? List.of() : List.copyOf(activeSourceIps);
        }
    }

    public record UserPage(List<UserSummary> items, int page, int pageSize, long total) {
    }

    public record NodeAccessInfo(
            String ip,
            Integer port,
            String username,
            String password,
            String countryCode,
            String countryName,
            String cityName
    ) {
    }

    public record SocksConnection(String host, int port, String username, String password) {
    }

    public record UserConnection(
            boolean success,
            String userId,
            String uuid,
            List<String> protocols,
            String vless,
            String vmess,
            SocksConnection socks,
            boolean proxyBound,
            Instant createdAt,
            Map<String, String> protocolsAll,
            Map<String, Object> protocolInfo
    ) {
        public UserConnection(boolean success,
                              String userId,
                              String uuid,
                              List<String> protocols,
                              String vless,
                              String vmess,
                              SocksConnection socks,
                              boolean proxyBound,
                              Instant createdAt) {
            this(success, userId, uuid, protocols, vless, vmess, socks, proxyBound, createdAt, Map.of(), Map.of());
        }

        public UserConnection(boolean success,
                              String userId,
                              String uuid,
                              List<String> protocols,
                              String vless,
                              String vmess,
                              SocksConnection socks,
                              boolean proxyBound,
                              Instant createdAt,
                              Map<String, String> protocolsAll) {
            this(success, userId, uuid, protocols, vless, vmess, socks, proxyBound, createdAt, protocolsAll, Map.of());
        }

        public UserConnection {
            protocolsAll = protocolsAll == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(protocolsAll));
            protocolInfo = protocolInfo == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(protocolInfo));
        }
    }

    public record ProxyDetails(
            String userId,
            boolean proxyBound,
            String nodeHost,
            String server,
            Integer port,
            String username,
            String password,
            String sourceIp,
            String sourceAddress,
            Integer sourcePort,
            String countryCode,
            String countryName,
            String cityName,
            Map<String, Object> protocolInfo
    ) {
        public ProxyDetails {
            protocolInfo = protocolInfo == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(protocolInfo));
        }
    }

    public record ProxyConfig(
            String type,
            @NotBlank(message = "代理服务器不能为空")
            @Size(max = 255, message = "代理服务器不能超过 255 个字符") String server,
            @Min(value = 1, message = "代理端口不能小于 1")
            @Max(value = 65535, message = "代理端口不能超过 65535") int port,
            @Size(max = 255, message = "代理用户名不能超过 255 个字符") String username,
            @Size(max = 255, message = "代理密码不能超过 255 个字符") String password,
            @Size(max = 255) String sourceIp,
            @Size(max = 255) String sourceAddress,
            @Size(max = 8) String countryCode,
            @Size(max = 255) String countryName,
            @Size(max = 255) String cityName
    ) {
        public ProxyConfig {
            type = type == null || type.isBlank() ? "socks5" : type;
        }

        public ProxyConfig(String type, String server, int port, String username, String password) {
            this(type, server, port, username, password, null, null, null, null, null);
        }
    }

    public record CreateUserRequest(
            @NotBlank(message = "用户 ID 不能为空")
            @Size(max = 64, message = "用户 ID 不能超过 64 个字符")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "用户 ID 只能包含字母、数字、点、下划线和短横线") String userId,
            @NotEmpty(message = "至少选择一种协议")
            List<@Pattern(regexp = "vless|vmess|socks", message = "协议只支持 VLESS、VMess 或 SOCKS") String> protocols,
            @Size(max = 255, message = "SOCKS 用户名不能超过 255 个字符") String socksUsername,
            @Size(max = 255, message = "SOCKS 密码不能超过 255 个字符") String socksPassword,
            @Valid ProxyConfig proxy,
            @Min(value = 0, message = "流量额度不能小于 0") Long trafficLimitBytes,
            @Min(value = 0, message = "最大来源 IP 数不能小于 0")
            @Max(value = 1000, message = "最大来源 IP 数不能超过 1000") Integer maxSourceIps
    ) {
        public CreateUserRequest(String userId,
                                 List<String> protocols,
                                 String socksUsername,
                                 String socksPassword,
                                 ProxyConfig proxy) {
            this(userId, protocols, socksUsername, socksPassword, proxy, null, null);
        }
    }

    public record UpdateUserPolicyRequest(
            @Min(value = 0, message = "流量额度不能小于 0") Long trafficLimitBytes,
            @Min(value = 0, message = "最大来源 IP 数不能小于 0")
            @Max(value = 1000, message = "最大来源 IP 数不能超过 1000") Integer maxSourceIps
    ) {
    }

    public record UserPolicyResponse(
            boolean success,
            String userId,
            Long trafficLimitBytes,
            Integer maxSourceIps
    ) {
    }

    public record CreateUserResponse(
            boolean success,
            String userId,
            String uuid,
            List<String> protocols,
            String vless,
            String vmess,
            SocksConnection socks,
            boolean proxyBound,
            Map<String, String> protocolsAll,
            Map<String, Object> protocolInfo
    ) {
        public CreateUserResponse(boolean success,
                                  String userId,
                                  String uuid,
                                  List<String> protocols,
                                  String vless,
                                  String vmess,
                                  SocksConnection socks,
                                  boolean proxyBound) {
            this(success, userId, uuid, protocols, vless, vmess, socks, proxyBound, Map.of(), Map.of());
        }

        public CreateUserResponse(boolean success,
                                  String userId,
                                  String uuid,
                                  List<String> protocols,
                                  String vless,
                                  String vmess,
                                  SocksConnection socks,
                                  boolean proxyBound,
                                  Map<String, String> protocolsAll) {
            this(success, userId, uuid, protocols, vless, vmess, socks, proxyBound, protocolsAll, Map.of());
        }

        public CreateUserResponse {
            protocolsAll = protocolsAll == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(protocolsAll));
            protocolInfo = protocolInfo == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(protocolInfo));
        }
    }

    public record BindProxyRequest(
            @NotBlank(message = "用户 ID 不能为空")
            @Size(max = 64, message = "用户 ID 不能超过 64 个字符")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "用户 ID 只能包含字母、数字、点、下划线和短横线") String userId,
            @Valid ProxyConfig proxy
    ) {
    }

    public record ProxyMetadataUpdateRequest(
            @Size(max = 255) String sourceIp,
            @Size(max = 255) String sourceAddress,
            @Min(value = 1) @Max(value = 65535) Integer sourcePort,
            @Size(max = 8) String countryCode,
            @Size(max = 255) String countryName,
            @Size(max = 255) String cityName
    ) {
    }

    public record OperationResponse(boolean success, String userId, String message) {
    }

    public record TrafficResponse(
            String userId,
            long upload,
            long download,
            long total,
            boolean available,
            String source,
            Instant collectedAt,
            Long trafficLimitBytes,
            Integer maxSourceIps,
            List<String> activeSourceIps,
            String status
    ) {
        public TrafficResponse(String userId,
                               long upload,
                               long download,
                               long total,
                               boolean available,
                               String source,
                               Instant collectedAt) {
            this(userId, upload, download, total, available, source, collectedAt,
                    null, null, List.of(), "active");
        }

        public TrafficResponse {
            activeSourceIps = activeSourceIps == null ? List.of() : List.copyOf(activeSourceIps);
            status = status == null || status.isBlank() ? "active" : status;
        }
    }

    public record ReloadResponse(boolean success) {
    }
}
