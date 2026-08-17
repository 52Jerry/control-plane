package com.example.nodecontrol.domain;

import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "residential_allocations")
public class ResidentialAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 128)
    private String requestKey;

    @Column(nullable = false, length = 128)
    private String requestHash;

    /**
     * Node user ids are scoped to a Node Manager.  They must not be globally
     * unique across the control plane because two different nodes may both
     * legitimately have a user named "alice".
     */
    @Column(nullable = false, length = 64)
    private String controlUserId;

    @Column(nullable = false, length = 128)
    private String remoteIdempotencyKey;

    @Column(nullable = false, length = 255)
    private String protocols;

    private Long trafficLimitBytes;

    private Integer maxSourceIps;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(length = 32)
    private String provisioningMode;

    private Integer sourceRowNumber;

    @Column(length = 255)
    private String proxySourceIp;

    @Column(length = 255)
    private String proxySourceDomain;

    private Integer proxySourcePort;

    @Column(length = 255)
    private String proxyServer;

    private Integer proxyPort;

    @Lob
    private String proxyUsernameCipher;

    @Column(length = 2048)
    private String proxyPasswordCipher;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id")
    private ManagedNode node;

    @Column(length = 64)
    private String remoteUserId;

    @Column(length = 128)
    private String connectionUuid;

    @Lob
    private String vlessCipher;

    @Lob
    private String vmessCipher;

    /** AES-GCM encrypted JSON map returned by Node Manager for all protocols. */
    @Lob
    private String protocolsAllCipher;

    /** AES-GCM encrypted structured protocol parameters used by the frontend to build links. */
    @Lob
    private String protocolInfoCipher;

    @Column(length = 255)
    private String socksHost;

    private Integer socksPort;

    @Column(length = 2048)
    private String socksUsernameCipher;

    @Column(length = 2048)
    private String socksPasswordCipher;

    @Column(nullable = false)
    private boolean proxyBound;

    @Column(length = 1000)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant completedAt;

    protected ResidentialAllocation() {
    }

    public ResidentialAllocation(String requestKey,
                                 String requestHash,
                                 String controlUserId,
                                 String remoteIdempotencyKey,
                                 String protocols) {
        this(requestKey, requestHash, controlUserId, remoteIdempotencyKey, protocols,
                "DIRECT", null, null, null, null, null, null, null, null);
    }

    public ResidentialAllocation(String requestKey,
                                 String requestHash,
                                 String controlUserId,
                                 String remoteIdempotencyKey,
                                 String protocols,
                                 String provisioningMode,
                                 Integer sourceRowNumber,
                                 String proxySourceIp,
                                 String proxySourceDomain,
                                 Integer proxySourcePort,
                                 String proxyServer,
                                 Integer proxyPort,
                                 String proxyUsernameCipher,
                                 String proxyPasswordCipher) {
        this.requestKey = requestKey;
        this.requestHash = requestHash;
        this.controlUserId = controlUserId;
        this.remoteIdempotencyKey = remoteIdempotencyKey;
        this.protocols = protocols;
        this.state = "PENDING";
        this.provisioningMode = provisioningMode;
        this.sourceRowNumber = sourceRowNumber;
        this.proxySourceIp = proxySourceIp;
        this.proxySourceDomain = proxySourceDomain;
        this.proxySourcePort = proxySourcePort;
        this.proxyServer = proxyServer;
        this.proxyPort = proxyPort;
        this.proxyUsernameCipher = proxyUsernameCipher;
        this.proxyPasswordCipher = proxyPasswordCipher;
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

    public void assignNode(ManagedNode node) {
        this.node = node;
        this.remoteUserId = controlUserId;
        this.state = "PROVISIONING";
        this.lastError = null;
    }

    public void markProvisioning() {
        this.state = "PROVISIONING";
        this.lastError = null;
    }

    public void setUserPolicy(Long trafficLimitBytes, Integer maxSourceIps) {
        this.trafficLimitBytes = normalizePolicyValue(trafficLimitBytes);
        this.maxSourceIps = normalizePolicyValue(maxSourceIps);
    }

    private Long normalizePolicyValue(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private Integer normalizePolicyValue(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    public void complete(CreateUserResponse response,
                         String encryptedVless,
                         String encryptedVmess,
                         String encryptedSocksUsername,
                         String encryptedSocksPassword) {
        complete(response, encryptedVless, encryptedVmess, null, null,
                encryptedSocksUsername, encryptedSocksPassword);
    }

    public void complete(CreateUserResponse response,
                         String encryptedVless,
                         String encryptedVmess,
                         String encryptedProtocolsAll,
                         String encryptedProtocolInfo,
                         String encryptedSocksUsername,
                         String encryptedSocksPassword) {
        this.state = "ACTIVE";
        this.remoteUserId = response.userId();
        this.connectionUuid = response.uuid();
        this.vlessCipher = encryptedVless;
        this.vmessCipher = encryptedVmess;
        this.protocolsAllCipher = encryptedProtocolsAll;
        this.protocolInfoCipher = encryptedProtocolInfo;
        if (response.socks() != null) {
            this.socksHost = response.socks().host();
            this.socksPort = response.socks().port();
            this.socksUsernameCipher = encryptedSocksUsername;
            this.socksPasswordCipher = encryptedSocksPassword;
        } else {
            this.socksHost = null;
            this.socksPort = null;
            this.socksUsernameCipher = null;
            this.socksPasswordCipher = null;
        }
        this.proxyBound = response.proxyBound();
        this.lastError = null;
        this.completedAt = Instant.now();
    }

    public void fail(String error, boolean releaseNode) {
        this.state = releaseNode ? "FAILED" : "RETRYABLE";
        this.lastError = error;
        if (releaseNode) {
            this.node = null;
        }
    }

    /** Detach terminal history from a node before that node is removed. */
    public void detachNode() {
        this.node = null;
    }

    public UUID getId() {
        return id;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getControlUserId() {
        return controlUserId;
    }

    public String getRemoteIdempotencyKey() {
        return remoteIdempotencyKey;
    }

    public String getProtocols() {
        return protocols;
    }

    public Long getTrafficLimitBytes() {
        return trafficLimitBytes;
    }

    public Integer getMaxSourceIps() {
        return maxSourceIps;
    }

    public String getState() {
        return state;
    }

    public String getProvisioningMode() {
        return provisioningMode == null || provisioningMode.isBlank() ? "DIRECT" : provisioningMode;
    }

    public Integer getSourceRowNumber() {
        return sourceRowNumber;
    }

    public String getProxySourceIp() {
        return proxySourceIp;
    }

    public String getProxySourceDomain() {
        return proxySourceDomain;
    }

    public Integer getProxySourcePort() {
        return proxySourcePort;
    }

    public String getProxyServer() {
        return proxyServer;
    }

    public Integer getProxyPort() {
        return proxyPort;
    }

    public String getProxyUsernameCipher() {
        return proxyUsernameCipher;
    }

    public String getProxyPasswordCipher() {
        return proxyPasswordCipher;
    }

    public ManagedNode getNode() {
        return node;
    }

    public String getRemoteUserId() {
        return remoteUserId;
    }

    public String getConnectionUuid() {
        return connectionUuid;
    }

    public String getVlessCipher() {
        return vlessCipher;
    }

    public String getVmessCipher() {
        return vmessCipher;
    }

    public String getProtocolsAllCipher() {
        return protocolsAllCipher;
    }

    public String getProtocolInfoCipher() {
        return protocolInfoCipher;
    }

    public String getSocksHost() {
        return socksHost;
    }

    public Integer getSocksPort() {
        return socksPort;
    }

    public String getSocksUsernameCipher() {
        return socksUsernameCipher;
    }

    public String getSocksPasswordCipher() {
        return socksPasswordCipher;
    }

    public boolean isProxyBound() {
        return proxyBound;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
