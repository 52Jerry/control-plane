package com.example.nodecontrol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "control_plane_settings")
public class ControlPlaneSettings {

    public static final int SINGLETON_ID = 1;
    public static final long INITIAL_TRAFFIC_LIMIT_BYTES = 200L * 1024 * 1024 * 1024;
    public static final int INITIAL_MAX_SOURCE_IPS = 5;

    @Id
    private Integer id;

    @Column(name = "default_traffic_limit_bytes", nullable = false)
    private long defaultTrafficLimitBytes;

    @Column(name = "default_max_source_ips", nullable = false)
    private int defaultMaxSourceIps;

    protected ControlPlaneSettings() {
    }

    public ControlPlaneSettings(long defaultTrafficLimitBytes, int defaultMaxSourceIps) {
        this.id = SINGLETON_ID;
        this.defaultTrafficLimitBytes = defaultTrafficLimitBytes;
        this.defaultMaxSourceIps = defaultMaxSourceIps;
    }

    public Integer getId() {
        return id;
    }

    public long getDefaultTrafficLimitBytes() {
        return defaultTrafficLimitBytes;
    }

    public int getDefaultMaxSourceIps() {
        return defaultMaxSourceIps;
    }
}
