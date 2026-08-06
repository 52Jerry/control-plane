package com.example.nodecontrol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "control-plane")
public class ControlPlaneProperties {

    private final Heartbeat heartbeat = new Heartbeat();
    private final Bootstrap bootstrap = new Bootstrap();
    private final Security security = new Security();
    private final Provisioning provisioning = new Provisioning();
    private final Installation installation = new Installation();
    private final GeoIp geoIp = new GeoIp();
    private String publicUrl = "";

    public Heartbeat getHeartbeat() {
        return heartbeat;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public Security getSecurity() {
        return security;
    }

    public Provisioning getProvisioning() {
        return provisioning;
    }

    public Installation getInstallation() {
        return installation;
    }

    public GeoIp getGeoIp() {
        return geoIp;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }

    public static class Heartbeat {
        private boolean scheduledEnabled = true;
        private long intervalMs = 15000;
        private int failureThreshold = 3;
        private long offlineAfterMs = 90000;

        public boolean isScheduledEnabled() {
            return scheduledEnabled;
        }

        public void setScheduledEnabled(boolean scheduledEnabled) {
            this.scheduledEnabled = scheduledEnabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getOfflineAfterMs() {
            return offlineAfterMs;
        }

        public void setOfflineAfterMs(long offlineAfterMs) {
            this.offlineAfterMs = offlineAfterMs;
        }
    }

    public static class Bootstrap {
        private boolean enabled = true;
        private String name = "默认节点";
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
        private String registrationToken = "";
        private String encryptionKey = "";
        private String loginUsername = "";
        private String loginPassword = "";
        private long sessionTtlSeconds = 43200;

        public String getAdminToken() {
            return adminToken;
        }

        public void setAdminToken(String adminToken) {
            this.adminToken = adminToken;
        }

        public String getRegistrationToken() {
            return registrationToken;
        }

        public void setRegistrationToken(String registrationToken) {
            this.registrationToken = registrationToken;
        }

        public String getEncryptionKey() {
            return encryptionKey;
        }

        public void setEncryptionKey(String encryptionKey) {
            this.encryptionKey = encryptionKey;
        }

        public String getLoginUsername() {
            return loginUsername;
        }

        public void setLoginUsername(String loginUsername) {
            this.loginUsername = loginUsername;
        }

        public String getLoginPassword() {
            return loginPassword;
        }

        public void setLoginPassword(String loginPassword) {
            this.loginPassword = loginPassword;
        }

        public long getSessionTtlSeconds() {
            return sessionTtlSeconds;
        }

        public void setSessionTtlSeconds(long sessionTtlSeconds) {
            this.sessionTtlSeconds = sessionTtlSeconds;
        }
    }

    public static class Provisioning {
        private int defaultMaxUsers = 500;
        private long operationStaleAfterMs = 120000;
        /** Whether residential allocations must include all five generated links. */
        private boolean requireCompleteProtocolsAll = false;

        public int getDefaultMaxUsers() {
            return defaultMaxUsers;
        }

        public void setDefaultMaxUsers(int defaultMaxUsers) {
            this.defaultMaxUsers = defaultMaxUsers;
        }

        public long getOperationStaleAfterMs() {
            return operationStaleAfterMs;
        }

        public void setOperationStaleAfterMs(long operationStaleAfterMs) {
            this.operationStaleAfterMs = operationStaleAfterMs;
        }

        public boolean isRequireCompleteProtocolsAll() {
            return requireCompleteProtocolsAll;
        }

        public void setRequireCompleteProtocolsAll(boolean requireCompleteProtocolsAll) {
            this.requireCompleteProtocolsAll = requireCompleteProtocolsAll;
        }
    }

    public static class Installation {
        private long tokenTtlSeconds = 600;
        private long claimTtlSeconds = 120;
        private String scriptUrl = "https://raw.githubusercontent.com/52Jerry/Node-Manager/main/install.sh";

        public long getTokenTtlSeconds() {
            return tokenTtlSeconds;
        }

        public void setTokenTtlSeconds(long tokenTtlSeconds) {
            this.tokenTtlSeconds = tokenTtlSeconds;
        }

        public long getClaimTtlSeconds() {
            return claimTtlSeconds;
        }

        public void setClaimTtlSeconds(long claimTtlSeconds) {
            this.claimTtlSeconds = claimTtlSeconds;
        }

        public String getScriptUrl() {
            return scriptUrl;
        }

        public void setScriptUrl(String scriptUrl) {
            this.scriptUrl = scriptUrl;
        }
    }

    public static class GeoIp {
        private boolean enabled = true;
        private String baseUrl = "https://get.geojs.io/v1/ip/geo";
        private int connectTimeoutMillis = 1500;
        private int readTimeoutMillis = 2500;
        private long cacheTtlSeconds = 86400;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        public void setConnectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        public int getReadTimeoutMillis() {
            return readTimeoutMillis;
        }

        public void setReadTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
        }

        public long getCacheTtlSeconds() {
            return cacheTtlSeconds;
        }

        public void setCacheTtlSeconds(long cacheTtlSeconds) {
            this.cacheTtlSeconds = cacheTtlSeconds;
        }
    }

}

