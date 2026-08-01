package com.example.nodecontrol.security;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.domain.ControlUser;
import com.example.nodecontrol.domain.ControlUserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlSessionServiceTest {

    @Test
    void createsSecureDatabaseBackedSessionCookie() {
        Fixture fixture = fixture();

        String token = fixture.service.createSessionToken(fixture.user);
        assertThat(fixture.service.hasValidSession(requestWithSession(token))).isTrue();
        assertThat(fixture.service.authenticatedSession(requestWithSession(token)).orElseThrow().username())
                .isEqualTo("control-admin");
        assertThat(fixture.service.sessionCookie(token, true).toString())
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/");
    }

    @Test
    void passwordResetOrDisableImmediatelyInvalidatesExistingSession() {
        Fixture fixture = fixture();
        String passwordResetToken = fixture.service.createSessionToken(fixture.user);

        fixture.user.changePasswordHash("new-hash");
        assertThat(fixture.service.hasValidSession(requestWithSession(passwordResetToken))).isFalse();

        String disableToken = fixture.service.createSessionToken(fixture.user);
        fixture.user.setEnabled(false);
        assertThat(fixture.service.hasValidSession(requestWithSession(disableToken))).isFalse();
    }

    @Test
    void rejectsTamperedExpiredUnknownAndLegacySessions() {
        Fixture fixture = fixture();
        String token = fixture.service.createSessionToken(fixture.user);

        assertThat(fixture.service.hasValidSession(requestWithSession(token + "tampered"))).isFalse();
        assertThat(fixture.service.hasValidSession(requestWithSession("v1.legacy.session"))).isFalse();
        when(fixture.repository.findById(fixture.user.getId())).thenReturn(Optional.empty());
        assertThat(fixture.service.hasValidSession(requestWithSession(token))).isFalse();
    }

    private Fixture fixture() {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.getSecurity().setEncryptionKey("session-test-encryption-key");
        properties.getSecurity().setLoginUsername("bootstrap-admin");
        properties.getSecurity().setLoginPassword("bootstrap-password");
        properties.getSecurity().setSessionTtlSeconds(3600);

        ControlUserRepository repository = mock(ControlUserRepository.class);
        ControlUser user = new ControlUser("control-admin", "password-hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));
        when(repository.existsByEnabledTrue()).thenReturn(true);

        return new Fixture(new ControlSessionService(properties, repository), repository, user);
    }

    private MockHttpServletRequest requestWithSession(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(ControlSessionService.COOKIE_NAME, token));
        return request;
    }

    private record Fixture(ControlSessionService service,
                           ControlUserRepository repository,
                           ControlUser user) {
    }
}
