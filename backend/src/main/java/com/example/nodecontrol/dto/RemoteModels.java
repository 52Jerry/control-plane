package com.example.nodecontrol.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
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
            TrafficTotals traffic,
            Instant reportedAt
    ) {
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
            String status,
            Instant createdAt
    ) {
    }

    public record UserPage(List<UserSummary> items, int page, int pageSize, long total) {
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
            Instant createdAt
    ) {
    }

    public record ProxyConfig(
            String type,
            @NotBlank @Size(max = 255) String server,
            @Min(1) @Max(65535) int port,
            @Size(max = 255) String username,
            @Size(max = 255) String password
    ) {
        public ProxyConfig {
            type = type == null || type.isBlank() ? "socks5" : type;
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String userId,
            @NotEmpty List<@Pattern(regexp = "vless|vmess|socks") String> protocols,
            @Size(max = 255) String socksUsername,
            @Size(max = 255) String socksPassword,
            @Valid ProxyConfig proxy
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
            boolean proxyBound
    ) {
    }

    public record BindProxyRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String userId,
            @Valid ProxyConfig proxy
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
            Instant collectedAt
    ) {
    }

    public record ReloadResponse(boolean success) {
    }
}

