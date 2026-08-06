package com.example.nodecontrol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "control_audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_username", length = 64)
    private String actorUsername;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id", length = 128)
    private String targetId;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(String eventType, UUID actorUserId, String actorUsername,
                    String targetType, String targetId, String summary) {
        this.eventType = eventType;
        this.actorUserId = actorUserId;
        this.actorUsername = actorUsername;
        this.targetType = targetType;
        this.targetId = targetId;
        this.summary = summary;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public UUID getActorUserId() { return actorUserId; }
    public String getActorUsername() { return actorUsername; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getSummary() { return summary; }
    public Instant getCreatedAt() { return createdAt; }
}
