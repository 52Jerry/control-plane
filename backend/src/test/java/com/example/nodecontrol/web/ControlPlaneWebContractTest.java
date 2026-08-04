package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.ControlPlaneModels.AgentRegistrationResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.AllocationView;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionBatchResponse;
import com.example.nodecontrol.dto.RemoteModels.SocksConnection;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.domain.NodeInstallToken;
import com.example.nodecontrol.domain.NodeInstallTokenRepository;
import com.example.nodecontrol.security.ControlSessionService;
import com.example.nodecontrol.service.ManagedNodeService;
import com.example.nodecontrol.service.NodeInstallationService;
import com.example.nodecontrol.service.ProvisioningService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:web-contract;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "control-plane.bootstrap.enabled=false",
        "control-plane.security.admin-token=admin-secret",
        "control-plane.security.registration-token=registration-secret",
        "control-plane.security.encryption-key=web-contract-encryption-key",
        "control-plane.security.login-username=control-admin",
        "control-plane.security.login-password=login-secret"
})
@AutoConfigureMockMvc
class ControlPlaneWebContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ManagedNodeService managedNodeService;

    @MockitoBean
    private ProvisioningService provisioningService;

    @Autowired
    private NodeInstallTokenRepository installTokenRepository;

    @Test
    void registrationUsesSeparateRegistrationToken() throws Exception {
        String body = """
                {
                  "nodeId":"node-1",
                  "name":"Node 1",
                  "baseUrl":"http://203.0.113.10:8088",
                  "apiToken":"node-token",
                  "host":"203.0.113.10",
                  "managerVersion":"1.4.1",
                  "maxUsers":500
                }
                """;

        mockMvc.perform(post("/api/control/agent/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/control/agent/register")
                        .header("X-Control-Token", "admin-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        UUID id = UUID.randomUUID();
        when(managedNodeService.registerAgent(any()))
                .thenReturn(new AgentRegistrationResponse(id, "node-1", "online", true));
        mockMvc.perform(post("/api/control/agent/register")
                        .header("X-Registration-Token", "registration-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.created").value(true));
        verify(managedNodeService).registerAgent(any());
    }

    @Test
    void passwordSessionCreatesOneTimeInstallCommandThatRegistersOnlyOnce() throws Exception {
        mockMvc.perform(post("/api/control/node-installation"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));

        Cookie adminCookie = loginCookie("control-admin", "login-secret");
        MvcResult commandResult = mockMvc.perform(post("/api/control/node-installation")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.command").value(org.hamcrest.Matchers.containsString(
                        "raw.githubusercontent.com/52Jerry/Node-Manager/main/install.sh")))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.expiresInSeconds").value(600))
                .andReturn();

        String command = com.jayway.jsonpath.JsonPath.read(
                commandResult.getResponse().getContentAsString(), "$.command");
        Matcher matcher = Pattern.compile("niusu_[A-Za-z0-9_-]+").matcher(command);
        assertThat(matcher.find()).isTrue();
        String installToken = matcher.group();
        assertThat(installTokenRepository.findAll())
                .anySatisfy(token -> assertThat(token.getTokenHash())
                        .isEqualTo(NodeInstallationService.hash(installToken)));
        assertThat(installTokenRepository.findAll())
                .allSatisfy(token -> assertThat(token.getTokenHash()).doesNotContain(installToken));

        UUID id = UUID.randomUUID();
        when(managedNodeService.registerAgent(any()))
                .thenReturn(new AgentRegistrationResponse(id, "one-click-node", "online", true));
        String body = registrationBody("one-click-node");
        mockMvc.perform(post("/api/control/agent/register")
                        .header("X-Install-Token", installToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(id.toString()));

        mockMvc.perform(post("/api/control/agent/register")
                        .header("X-Install-Token", installToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        verify(managedNodeService, times(1)).registerAgent(any());
    }

    @Test
    void failedNodeVerificationReleasesInstallTokenForInstallerRetry() throws Exception {
        Cookie adminCookie = loginCookie("control-admin", "login-secret");
        MvcResult commandResult = mockMvc.perform(post("/api/control/node-installation").cookie(adminCookie))
                .andExpect(status().isOk())
                .andReturn();
        String command = com.jayway.jsonpath.JsonPath.read(
                commandResult.getResponse().getContentAsString(), "$.command");
        Matcher matcher = Pattern.compile("niusu_[A-Za-z0-9_-]+").matcher(command);
        assertThat(matcher.find()).isTrue();
        String installToken = matcher.group();
        UUID id = UUID.randomUUID();
        when(managedNodeService.registerAgent(any()))
                .thenThrow(new IllegalArgumentException("节点尚未就绪"))
                .thenReturn(new AgentRegistrationResponse(id, "retry-node", "online", true));
        String body = registrationBody("retry-node");

        mockMvc.perform(post("/api/control/agent/register")
                        .header("X-Install-Token", installToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/control/agent/register")
                        .header("X-Install-Token", installToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        verify(managedNodeService, times(2)).registerAgent(any());
    }

    @Test
    void expiredInstallTokenCannotRegisterNode() throws Exception {
        String rawToken = "niusu_expired_web_contract_token";
        Instant now = Instant.now();
        installTokenRepository.save(new NodeInstallToken(
                NodeInstallationService.hash(rawToken),
                "test",
                now.minusSeconds(120),
                now.minusSeconds(60)));

        mockMvc.perform(post("/api/control/agent/register")
                        .header("X-Install-Token", rawToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("expired-node")))
                .andExpect(status().isUnauthorized());
        verify(managedNodeService, times(0)).registerAgent(any());
    }

    @Test
    void adminApiRequiresAdminTokenAndAllocationResponsesAreNotCached() throws Exception {
        mockMvc.perform(get("/api/control/allocations"))
                .andExpect(status().isUnauthorized());

        AllocationView allocation = new AllocationView(
                UUID.randomUUID(), "request-1", "customer-1", List.of("socks"), "PENDING",
                null, null, null, null, null, Instant.now(), Instant.now(), null);
        when(provisioningService.listAllocations()).thenReturn(List.of(allocation));
        mockMvc.perform(get("/api/control/allocations")
                        .header("X-Control-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].userId").value("customer-1"));
    }

    @Test
    void connectionSecretsAreOnlyReturnedBySingleAllocationEndpoint() throws Exception {
        UUID id = UUID.randomUUID();
        UserConnection connection = new UserConnection(
                true, "customer-2", UUID.randomUUID().toString(), List.of("socks"), null, null,
                new SocksConnection("203.0.113.20", 5001, "access-user", "access-password"),
                false, Instant.now());
        AllocationView summary = new AllocationView(
                id, "request-2", "customer-2", List.of("socks"), "ACTIVE",
                UUID.randomUUID(), "Node 1", "203.0.113.10", null, null,
                Instant.now(), Instant.now(), Instant.now());
        AllocationView detail = new AllocationView(
                summary.id(), summary.requestKey(), summary.userId(), summary.protocols(), summary.state(),
                summary.nodeId(), summary.nodeName(), summary.nodeHost(), connection, null,
                summary.createdAt(), summary.updatedAt(), summary.completedAt());
        when(provisioningService.listAllocations()).thenReturn(List.of(summary));
        when(provisioningService.getAllocation(id)).thenReturn(detail);

        mockMvc.perform(get("/api/control/allocations")
                        .header("X-Control-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].connection").doesNotExist());
        mockMvc.perform(get("/api/control/allocations/{id}", id)
                        .header("X-Control-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.connection.socks.password").value("access-password"));
    }

    @Test
    void passwordLoginCreatesHttpOnlySessionThatCanAccessAndLogoutFromControlApi() throws Exception {
        mockMvc.perform(get("/api/control/nodes"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));

        mockMvc.perform(post("/api/control/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"control-admin","password":"wrong-secret"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        MvcResult login = mockMvc.perform(post("/api/control/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"control-admin","password":"login-secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"))))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andReturn();

        Cookie sessionCookie = sessionCookie(login);
        when(managedNodeService.listNodes()).thenReturn(List.of());
        mockMvc.perform(get("/api/control/nodes").cookie(sessionCookie))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/control/auth/session").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.authenticated").value(true));

        mockMvc.perform(post("/api/control/auth/logout").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(ControlSessionService.COOKIE_NAME + "="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"))));
    }

    @Test
    void passwordSessionCanManageAccountsWithoutExposingPasswordHashes() throws Exception {
        mockMvc.perform(get("/api/control/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));

        Cookie adminCookie = loginCookie("control-admin", "login-secret");
        mockMvc.perform(get("/api/control/accounts").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].username").value("control-admin"))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());

        MvcResult created = mockMvc.perform(post("/api/control/accounts")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"web-operator","password":"operator-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.username").value("web-operator"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.current").value(false))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();
        String operatorId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id");

        Cookie operatorCookie = loginCookie("web-operator", "operator-password");
        mockMvc.perform(patch("/api/control/accounts/{userId}", operatorId)
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"replacement-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mockMvc.perform(get("/api/control/nodes").cookie(operatorCookie))
                .andExpect(status().isUnauthorized());

        Cookie replacementCookie = loginCookie("web-operator", "replacement-password");
        mockMvc.perform(patch("/api/control/accounts/{userId}", operatorId)
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(get("/api/control/nodes").cookie(replacementCookie))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/control/accounts/{userId}", operatorId)
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(delete("/api/control/accounts/{userId}", operatorId)
                        .cookie(adminCookie))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(post("/api/control/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"web-operator","password":"replacement-password"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyAdminTokenStillWorksAndBatchSecretsResponseIsNotCached() throws Exception {
        ProxyProvisionBatchResponse response = new ProxyProvisionBatchResponse(1, 0, 1, List.of());
        when(provisioningService.provisionProxyBatch(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/control/allocations/proxy-provisions")
                        .header("X-Control-Token", "admin-secret")
                        .header("Idempotency-Key", "batch-web-contract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "input":"198.51.100.10 example.test 1080 test-user test-secret",
                                  "protocols":["vless","vmess","socks"],
                                  "userPrefix":"batch"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.total").value(1));
    }

    private Cookie sessionCookie(MvcResult login) {
        String setCookie = login.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotBlank();
        String pair = setCookie.split(";", 2)[0];
        String[] nameAndValue = pair.split("=", 2);
        return new Cookie(nameAndValue[0], nameAndValue[1]);
    }

    private String registrationBody(String nodeId) {
        return """
                {
                  "nodeId":"%s",
                  "name":"One-click Node",
                  "baseUrl":"http://203.0.113.10:8088",
                  "apiToken":"node-token",
                  "host":"203.0.113.10",
                  "managerVersion":"1.4.1",
                  "maxUsers":500
                }
                """.formatted(nodeId);
    }

    private Cookie loginCookie(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/control/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        return sessionCookie(login);
    }
}
