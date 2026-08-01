package com.example.nodecontrol.service;

import com.example.nodecontrol.domain.ControlUser;
import com.example.nodecontrol.domain.ControlUserRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.CreateControlUserRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.UpdateControlUserRequest;
import com.example.nodecontrol.security.ControlSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:control-accounts;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "control-plane.bootstrap.enabled=false",
        "control-plane.security.encryption-key=account-test-encryption-key",
        "control-plane.security.login-username=initial-admin",
        "control-plane.security.login-password=initial-password"
})
class ControlAccountServiceIntegrationTest {

    @Autowired
    private ControlAccountService accountService;

    @Autowired
    private ControlUserRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ControlSessionService sessionService;

    private ControlUser initialAdmin;

    @BeforeEach
    void resetAccounts() {
        repository.deleteAll();
        accountService.bootstrapInitialAccount();
        initialAdmin = repository.findByUsernameIgnoreCase("initial-admin").orElseThrow();
    }

    @Test
    void bootstrapsInitialAccountOnlyWhenTableIsEmptyAndStoresBcryptHash() {
        assertThat(repository.count()).isEqualTo(1);
        assertThat(initialAdmin.getPasswordHash())
                .startsWith("$2")
                .doesNotContain("initial-password");
        assertThat(passwordEncoder.matches("initial-password", initialAdmin.getPasswordHash())).isTrue();

        accountService.bootstrapInitialAccount();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void createsAnotherEqualPrivilegeAccountAndAuthenticatesCaseInsensitively() {
        var created = accountService.create(
                new CreateControlUserRequest("operator.one", "operator-password"),
                initialAdmin.getId());

        assertThat(created.username()).isEqualTo("operator.one");
        assertThat(created.enabled()).isTrue();
        assertThat(created.current()).isFalse();
        assertThat(accountService.authenticate("OPERATOR.ONE", "operator-password")).isPresent();
        assertThat(accountService.authenticate("operator.one", "wrong-password")).isEmpty();
        assertThat(repository.findByUsernameIgnoreCase("operator.one").orElseThrow().getPasswordHash())
                .doesNotContain("operator-password");
    }

    @Test
    void passwordResetAndDisableRevokeExistingCookies() {
        var created = accountService.create(
                new CreateControlUserRequest("operator.two", "operator-password"),
                initialAdmin.getId());
        ControlUser operator = repository.findById(created.id()).orElseThrow();
        String passwordResetToken = sessionService.createSessionToken(operator);

        accountService.update(operator.getId(), new UpdateControlUserRequest(null, "replacement-password"), initialAdmin.getId());
        assertThat(sessionService.hasValidSession(requestWithSession(passwordResetToken))).isFalse();
        assertThat(accountService.authenticate("operator.two", "operator-password")).isEmpty();
        assertThat(accountService.authenticate("operator.two", "replacement-password")).isPresent();

        operator = repository.findById(operator.getId()).orElseThrow();
        String disableToken = sessionService.createSessionToken(operator);
        accountService.update(operator.getId(), new UpdateControlUserRequest(false, null), initialAdmin.getId());
        assertThat(sessionService.hasValidSession(requestWithSession(disableToken))).isFalse();
        assertThat(accountService.authenticate("operator.two", "replacement-password")).isEmpty();
    }

    @Test
    void protectsCurrentAccountAndLastEnabledAccount() {
        assertThatThrownBy(() -> accountService.update(
                initialAdmin.getId(), new UpdateControlUserRequest(false, null), initialAdmin.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("当前登录账号");
        assertThatThrownBy(() -> accountService.delete(initialAdmin.getId(), initialAdmin.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("当前登录账号");

        UUID unrelatedCurrentUser = UUID.randomUUID();
        assertThatThrownBy(() -> accountService.update(
                initialAdmin.getId(), new UpdateControlUserRequest(false, null), unrelatedCurrentUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少需要保留一个");
        assertThatThrownBy(() -> accountService.delete(initialAdmin.getId(), unrelatedCurrentUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少需要保留一个");
    }

    private MockHttpServletRequest requestWithSession(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(ControlSessionService.COOKIE_NAME, token));
        return request;
    }
}
