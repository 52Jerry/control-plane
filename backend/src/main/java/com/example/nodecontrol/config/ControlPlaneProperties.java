package com.example.nodecontrol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "control-plane")
public class ControlPlaneProperties {

    private final Heartbeat heartbeat = new Heartbeat();
    private final Bootstrap bootstrap = new Bootstrap();
    private final Security security = new Security();

    public Heartbeat getHeartbeat() {
        return heartbeat;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public Security getSecurity() {
        return security;
    }

    public static class Heartbeat {
        private long intervalMs = 15000;

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    public static class Bootstrap {
        private boolean enabled = true;
        private String name = "Default Node";
        private String baseUrl = "";
        private String token = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public static class Security {
        private String adminToken = "";

        public String getAdminToken() {
            return adminToken;
        }

        public void setAdminToken(String adminToken) {
            this.adminToken = adminToken;
        }
    }
}

