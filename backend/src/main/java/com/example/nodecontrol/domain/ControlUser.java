package com.example.nodecontrol.domain;

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
@Table(name = "control_users")
public class ControlUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, length = 32)
    private String role = "ADMIN";

    @Column(nullable = false)
    private long sessionVersion = 1;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant lastLoginAt;

    protected ControlUser() {
    }

    public ControlUser(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public ControlUser(String username, String passwordHash, String role) {
        this(username, passwordHash);
        setRole(role);
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

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        sessionVersion++;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            sessionVersion++;
        }
    }

    public void setRole(String role) {
        String normalized = role == null ? "ADMIN" : role.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("ADMIN", "NODE_OPS", "PROVISIONER", "READONLY").contains(normalized)) {
            throw new IllegalArgumentException("账号角色不受支持");
        }
        if (!normalized.equals(this.role)) {
            this.role = normalized;
            sessionVersion++;
        }
    }

    public void recordLogin() {
        lastLoginAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getRole() {
        return role == null ? "ADMIN" : role;
    }

    public long getSessionVersion() {
        return sessionVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
