package com.example.nodecontrol.service;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.domain.NodeInstallToken;
import com.example.nodecontrol.domain.NodeInstallTokenRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.AgentRegistrationResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.NodeInstallCommandResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class NodeInstallationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String TOKEN_PREFIX = "niusu_";

    private final NodeInstallTokenRepository repository;
    private final ControlPlaneProperties properties;
    private final AuditLogService auditLogService;

    public NodeInstallationService(NodeInstallTokenRepository repository,
                                   ControlPlaneProperties properties) {
        this(repository, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NodeInstallationService(NodeInstallTokenRepository repository,
                                   ControlPlaneProperties properties,
                                   AuditLogService auditLogService) {
        this.repository = repository;
        this.properties = properties;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public NodeInstallCommandResponse issueCommand(String createdBy, String controlPlaneUrl) {
        return issueCommand(createdBy, controlPlaneUrl, null);
    }

    @Transactional
    public NodeInstallCommandResponse issueCommand(String createdBy, String controlPlaneUrl, UUID actorUserId) {
        Instant now = Instant.now();
        long ttlSeconds = Math.max(60, properties.getInstallation().getTokenTtlSeconds());
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        String rawToken = newToken();
        repository.save(new NodeInstallToken(hash(rawToken), normalizeCreatedBy(createdBy), now, expiresAt));
        repository.deleteByExpiresAtBefore(now.minus(Duration.ofDays(7)));

        String scriptUrl = normalizeHttpUrl(properties.getInstallation().getScriptUrl(), "安装脚本地址");
        String command = "bash <(curl -fsSL " + shellQuote(scriptUrl) + ") "
                + shellQuote(normalizeHttpUrl(controlPlaneUrl, "控制中心公网地址")) + " "
                + shellQuote(rawToken);
        if (auditLogService != null) {
            auditLogService.record("NODE_INSTALL_COMMAND_ISSUED", actorUserId,
                    "NODE_INSTALLATION", normalizeCreatedBy(createdBy),
                    "生成 Node Manager 一键安装命令，过期时间 " + expiresAt);
        }
        return new NodeInstallCommandResponse(command, expiresAt, ttlSeconds);
    }

    public RegistrationPermit claim(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            throw invalidToken();
        }
        String tokenHash = hash(rawToken.trim());
        String claimId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        long claimTtlSeconds = Math.max(30, properties.getInstallation().getClaimTtlSeconds());
        int claimed = repository.claimAvailable(
                tokenHash,
                claimId,
                now,
                now,
                now.minusSeconds(claimTtlSeconds));
        if (claimed != 1) {
            throw invalidToken();
        }
        return new RegistrationPermit(this, tokenHash, claimId);
    }

    private void complete(String tokenHash, String claimId, AgentRegistrationResponse response) {
        int updated = repository.markUsed(tokenHash, claimId, Instant.now(), response.id());
        if (updated != 1) {
            throw new IllegalStateException("一次性安装码状态更新失败，请重新生成安装命令");
        }
    }

    private void release(String tokenHash, String claimId) {
        repository.releaseClaim(tokenHash, claimId);
    }

    private String newToken() {
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("一次性安装码摘要组件初始化失败", exception);
        }
    }

    private String normalizeCreatedBy(String createdBy) {
        if (!StringUtils.hasText(createdBy)) {
            return "control-api";
        }
        String value = createdBy.trim();
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private String normalizeHttpUrl(String rawUrl, String label) {
        if (!StringUtils.hasText(rawUrl)) {
            throw new IllegalStateException(label + "不能为空");
        }
        String value = rawUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(label + "格式不正确");
        }
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException(label + "必须是有效的 HTTP 或 HTTPS 地址");
        }
        return value;
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private ResponseStatusException invalidToken() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "一次性安装码无效、已过期或已使用");
    }

    public static final class RegistrationPermit implements AutoCloseable {
        private final NodeInstallationService service;
        private final String tokenHash;
        private final String claimId;
        private boolean completed;

        private RegistrationPermit(NodeInstallationService service, String tokenHash, String claimId) {
            this.service = service;
            this.tokenHash = tokenHash;
            this.claimId = claimId;
        }

        public void complete(AgentRegistrationResponse response) {
            service.complete(tokenHash, claimId, response);
            completed = true;
        }

        @Override
        public void close() {
            if (!completed) {
                service.release(tokenHash, claimId);
            }
        }
    }
}
