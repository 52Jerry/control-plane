package com.example.nodecontrol.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ControlPlaneModels {

    private ControlPlaneModels() {
    }

    public record MetaResponse(String version, boolean authRequired) {
    }

    public record RegisterNodeRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 1024) String token
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
            String lastError,
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
            long totalTraffic
    ) {
    }
}

