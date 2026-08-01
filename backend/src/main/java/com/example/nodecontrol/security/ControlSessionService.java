package com.example.nodecontrol.security;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.domain.ControlUser;
import com.example.nodecontrol.domain.ControlUserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Component
public class ControlSessionService {

    public static final String COOKIE_NAME = "NIUSU_CONTROL_SESSION";
    private static final String VERSION = "v2";

    private final ControlPlaneProperties properties;
    private final ControlUserRepository userRepository;
    private final SecretKeySpec signingKey;

    public ControlSessionService(ControlPlaneProperties properties,
                                 ControlUserRepository userRepository) {
        this.properties = properties;
        this.userRepository = userRepository;
        String keyMaterial = properties.getSecurity().getEncryptionKey();
        if (!StringUtils.hasText(keyMaterial)) {
            throw new IllegalStateException("CONTROL_PLANE_ENCRYPTION_KEY must not be empty");
        }
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(("control-session:" + keyMaterial).getBytes(StandardCharsets.UTF_8));
            this.signingKey = new SecretKeySpec(key, "HmacSHA256");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not initialize control session signing", exception);
        }
    }

    public boolean isPasswordLoginEnabled() {
        return userRepository.existsByEnabledTrue()
                || (StringUtils.hasText(properties.getSecurity().getLoginUsername())
                && StringUtils.hasText(properties.getSecurity().getLoginPassword()));
    }

    public String createSessionToken(ControlUser user) {
        long expiresAt = Instant.now().plusSeconds(sessionTtlSeconds()).getEpochSecond();
        String payload = VERSION + "." + user.getId() + "." + user.getSessionVersion() + "." + expiresAt;
        return payload + "." + sign(payload);
    }

    public boolean hasValidSession(HttpServletRequest request) {
        return authenticatedSession(request).isPresent();
    }

    public Optional<AuthenticatedSession> authenticatedSession(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return validate(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    public ResponseCookie sessionCookie(String token, boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofSeconds(sessionTtlSeconds()))
                .build();
    }

    public ResponseCookie expiredCookie(boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private Optional<AuthenticatedSession> validate(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 5 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }
        String payload = String.join(".", parts[0], parts[1], parts[2], parts[3]);
        if (!constantTimeEquals(sign(payload), parts[4])) {
            return Optional.empty();
        }
        try {
            UUID userId = UUID.fromString(parts[1]);
            long sessionVersion = Long.parseLong(parts[2]);
            long expiresAt = Long.parseLong(parts[3]);
            if (expiresAt <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return userRepository.findById(userId)
                    .filter(ControlUser::isEnabled)
                    .filter(user -> user.getSessionVersion() == sessionVersion)
                    .map(user -> new AuthenticatedSession(user.getId(), user.getUsername()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not sign control session", exception);
        }
    }

    private long sessionTtlSeconds() {
        return Math.max(300, properties.getSecurity().getSessionTtlSeconds());
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    public record AuthenticatedSession(UUID userId, String username) {
    }
}
