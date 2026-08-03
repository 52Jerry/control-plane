package com.example.nodecontrol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "node_install_tokens")
public class NodeInstallToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "claim_id", length = 36)
    private String claimId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_node_id")
    private UUID usedNodeId;

    protected NodeInstallToken() {
    }

    public NodeInstallToken(String tokenHash, String createdBy, Instant createdAt, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getClaimId() {
        return claimId;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public UUID getUsedNodeId() {
        return usedNodeId;
    }
}
