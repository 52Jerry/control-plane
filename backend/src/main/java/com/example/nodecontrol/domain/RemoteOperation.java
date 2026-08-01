package com.example.nodecontrol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "remote_operations")
public class RemoteOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 128)
    private String operationKey;

    @Column(nullable = false)
    private UUID nodeId;

    @Column(nullable = false, length = 64)
    private String operationType;

    @Column(nullable = false, length = 128)
    private String requestHash;

    @Column(nullable = false, length = 32)
    private String state;

    @Lob
    private String responseCipher;

    @Column(length = 1000)
    private String lastError;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RemoteOperation() {
    }

    public RemoteOperation(String operationKey, UUID nodeId, String operationType, String requestHash) {
        this.operationKey = operationKey;
        this.nodeId = nodeId;
        this.operationType = operationType;
        this.requestHash = requestHash;
        this.state = "IN_PROGRESS";
        this.attempts = 1;
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

    public void retry() {
        this.state = "IN_PROGRESS";
        this.lastError = null;
        this.attempts++;
    }

    public void succeed(String responseCipher) {
        this.state = "SUCCEEDED";
        this.responseCipher = responseCipher;
        this.lastError = null;
    }

    public void fail(String error) {
        this.state = "FAILED";
        this.lastError = error;
    }

    public UUID getId() {
        return id;
    }

    public String getOperationKey() {
        return operationKey;
    }

    public UUID getNodeId() {
        return nodeId;
    }

    public String getOperationType() {
        return operationType;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getState() {
        return state;
    }

    public String getResponseCipher() {
        return responseCipher;
    }

    public String getLastError() {
        return lastError;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
