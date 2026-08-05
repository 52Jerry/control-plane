package com.example.nodecontrol.domain;

import com.example.nodecontrol.dto.RemoteModels.AgentHeartbeat;
import com.example.nodecontrol.dto.RemoteModels.AgentInfo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "managed_nodes")
public class ManagedNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, unique = true, length = 500)
    private String baseUrl;

    @Column(name = "api_token", nullable = false, length = 2048)
    private String storedApiToken;

    @Column(unique = true, length = 128)
    private String remoteNodeId;

    @Column(length = 255)
    private String host;

    @Column(length = 64)
    private String managerVersion;

    @Column(length = 64)
    private String singboxVersion;

    @Column(length = 32)
    private String singbox;

    @Column(nullable = false, length = 32)
    private String status = "unknown";

    @Column(nullable = false)
    private boolean apiAvailable;

    @Column(nullable = false)
    private double cpu;

    @Column(nullable = false)
    private double memory;

    @Column(nullable = false)
    private int connections;

    @Column(nullable = false)
    private int systemConnections;

    @Column(nullable = false)
    private int userCount;

    /** Public SOCKS inbound port reported by Node Manager for loop protection. */
    @Column(name = "socks_inbound_port")
    private Integer socksInboundPort;

    @Column(nullable = false)
    private long upload;

    @Column(nullable = false)
    private long download;

    @Column(nullable = false)
    private long totalTraffic;

    private Instant reportedAt;
    private Instant lastCheckedAt;
    private Instant lastSuccessfulHeartbeatAt;

    @Column(length = 1000)
    private String lastError;

    @Column(nullable = false)
    private int consecutiveFailures;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean maintenance;

    @Column(nullable = false)
    private int maxUsers = 500;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ManagedNode() {
    }

    public ManagedNode(String name, String baseUrl, String storedApiToken) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.storedApiToken = storedApiToken;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void updateRegistration(String name,
                                   String baseUrl,
                                   String storedApiToken,
                                   AgentInfo info,
                                   int defaultMaxUsers) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.storedApiToken = storedApiToken;
        this.remoteNodeId = info.nodeId();
        this.managerVersion = info.managerVersion();
        if (this.maxUsers <= 0) {
            this.maxUsers = defaultMaxUsers;
        }
        this.enabled = true;
    }

    public void recordHeartbeat(AgentHeartbeat heartbeat) {
        Instant now = Instant.now();
        this.remoteNodeId = heartbeat.nodeId();
        this.host = heartbeat.host();
        this.managerVersion = heartbeat.managerVersion();
        this.singboxVersion = heartbeat.singboxVersion();
        this.singbox = heartbeat.singbox();
        this.status = heartbeat.status() == null ? "online" : heartbeat.status();
        this.apiAvailable = heartbeat.apiAvailable();
        this.cpu = heartbeat.cpu();
        this.memory = heartbeat.memory();
        this.connections = heartbeat.connections();
        this.systemConnections = heartbeat.systemConnections();
        this.userCount = heartbeat.userCount();
        this.socksInboundPort = heartbeat.socksPort();
        this.upload = heartbeat.traffic() == null ? 0 : heartbeat.traffic().upload();
        this.download = heartbeat.traffic() == null ? 0 : heartbeat.traffic().download();
        this.totalTraffic = heartbeat.traffic() == null ? 0 : heartbeat.traffic().total();
        this.reportedAt = heartbeat.reportedAt();
        this.lastCheckedAt = now;
        this.lastSuccessfulHeartbeatAt = now;
        this.lastError = null;
        this.consecutiveFailures = 0;
    }

    public void recordHeartbeatFailure(String error, int failureThreshold, Instant offlineBefore) {
        this.lastCheckedAt = Instant.now();
        this.lastError = error;
        this.consecutiveFailures++;
        if (this.consecutiveFailures >= failureThreshold
                || this.lastSuccessfulHeartbeatAt == null
                || this.lastSuccessfulHeartbeatAt.isBefore(offlineBefore)) {
            this.status = "offline";
            this.apiAvailable = false;
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getStoredApiToken() {
        return storedApiToken;
    }

    public void setStoredApiToken(String storedApiToken) {
        this.storedApiToken = storedApiToken;
    }

    public String getRemoteNodeId() {
        return remoteNodeId;
    }

    public String getHost() {
        return host;
    }

    public String getManagerVersion() {
        return managerVersion;
    }

    public String getSingboxVersion() {
        return singboxVersion;
    }

    public String getSingbox() {
        return singbox;
    }

    public String getStatus() {
        return status;
    }

    public boolean isApiAvailable() {
        return apiAvailable;
    }

    public double getCpu() {
        return cpu;
    }

    public double getMemory() {
        return memory;
    }

    public int getConnections() {
        return connections;
    }

    public int getSystemConnections() {
        return systemConnections;
    }

    public int getUserCount() {
        return userCount;
    }

    public Integer getSocksInboundPort() {
        return socksInboundPort;
    }

    public long getUpload() {
        return upload;
    }

    public long getDownload() {
        return download;
    }

    public long getTotalTraffic() {
        return totalTraffic;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public Instant getLastSuccessfulHeartbeatAt() {
        return lastSuccessfulHeartbeatAt;
    }

    public String getLastError() {
        return lastError;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMaintenance() {
        return maintenance;
    }

    public void setMaintenance(boolean maintenance) {
        this.maintenance = maintenance;
    }

    public int getMaxUsers() {
        return maxUsers;
    }

    public void setMaxUsers(int maxUsers) {
        this.maxUsers = maxUsers;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
