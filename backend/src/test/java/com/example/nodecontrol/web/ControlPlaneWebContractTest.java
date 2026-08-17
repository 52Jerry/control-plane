package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.ControlPlaneModels.AgentRegistrationResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.AllocationView;
import com.example.nodecontrol.dto.ControlPlaneModels.NodeTokenResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.NodeView;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionBatchResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.BatchConnectionResult;
import com.example.nodecontrol.dto.RemoteModels.OperationResponse;
import com.example.nodecontrol.dto.RemoteModels.SocksConnection;
import com.example.nodecontrol.dto.RemoteModels.UpdateUserPolicyRequest;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.UserPage;
import com.example.nodecontrol.dto.RemoteModels.UserPolicyResponse;
import com.example.nodecontrol.dto.RemoteModels.UserSummary;
import com.example.nodecontrol.domain.NodeInstallToken;
import com.example.nodecontrol.domain.NodeInstallTokenRepository;
import com.example.nodecontrol.security.ControlSessionService;
import com.example.nodecontrol.service.ManagedNodeService;
import com.example.nodecontrol.service.NodeInstallationService;
import com.example.nodecontrol.service.NodeUserService;
import com.example.nodecontrol.service.ProvisioningService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    @MockitoBean
    private NodeUserService nodeUserService;

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
        when(provisioningService.listAllocations(null, true)).thenReturn(List.of(allocation));
        mockMvc.perform(get("/api/control/allocations")
                        .header("X-Control-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].userId").value("customer-1"));
    }

    @Test
    void allocationPagingReturnsMetadataWhenRequested() throws Exception {
        AllocationView allocation = new AllocationView(
                UUID.randomUUID(), "request-page", "page-user", List.of("socks"), "PENDING",
                null, null, null, null, null, Instant.now(), Instant.now(), null);
        when(provisioningService.listAllocations(1, 20, null, true))
                .thenReturn(new com.example.nodecontrol.dto.ControlPlaneModels.AllocationPageResponse(
                        List.of(allocation), 1, 20, 1, 1));

        mockMvc.perform(get("/api/control/allocations")
                        .param("page", "1")
                        .param("pageSize", "20")
                        .header("X-Control-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].userId").value("page-user"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void nodeUserListForwardsIpSearchAndSortAndIsNotCached() throws Exception {
        UUID nodeId = UUID.randomUUID();
        when(nodeUserService.listUsers(nodeId, 1, 20, null, "198.51.100", "createdAsc", true))
                .thenReturn(new UserPage(List.of(), 1, 20, 0));

        mockMvc.perform(get("/api/control/nodes/{nodeId}/users", nodeId)
                        .param("ip", "198.51.100")
                        .param("sort", "createdAsc")
                        .header("X-Control-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.total").value(0));

        verify(nodeUserService).listUsers(
                nodeId, 1, 20, null, "198.51.100", "createdAsc", true);
    }

    @Test
    void nodeUserRefreshBypassesTheServiceSnapshot() throws Exception {
        UUID nodeId = UUID.randomUUID();
        when(nodeUserService.listUsers(
                nodeId, 2, 20, null, null, "createdDesc", true, true))
                .thenReturn(new UserPage(List.of(), 2, 20, 0));

        mockMvc.perform(get("/api/control/nodes/{nodeId}/users", nodeId)
                        .param("page", "2")
                        .param("refresh", "true")
                        .header("X-Control-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.page").value(2));

        verify(nodeUserService).listUsers(
                nodeId, 2, 20, null, null, "createdDesc", true, true);
    }

    @Test
    void nodeUserPolicyPatchForwardsLimitsAndIsNotCached() throws Exception {
        UUID nodeId = UUID.randomUUID();
        when(nodeUserService.updatePolicy(any(), any(), any(), any()))
                .thenReturn(new UserPolicyResponse(true, "user-1", 10737418240L, 2));

        mockMvc.perform(patch("/api/control/nodes/{nodeId}/users/{userId}/policy", nodeId, "user-1")
                        .header("X-Control-Token", "admin-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trafficLimitBytes\":10737418240,\"maxSourceIps\":2}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.trafficLimitBytes").value(10737418240L))
                .andExpect(jsonPath("$.maxSourceIps").value(2));

        ArgumentCaptor<UpdateUserPolicyRequest> requestCaptor =
                ArgumentCaptor.forClass(UpdateUserPolicyRequest.class);
        verify(nodeUserService).updatePolicy(
                org.mockito.ArgumentMatchers.eq(nodeId),
                org.mockito.ArgumentMatchers.eq("user-1"),
                requestCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(requestCaptor.getValue().trafficLimitBytes()).isEqualTo(10737418240L);
        assertThat(requestCaptor.getValue().maxSourceIps()).isEqualTo(2);
    }

    @Test
    void nodeUserExportReturnsOneCompleteListWithoutCaching() throws Exception {
        UUID nodeId = UUID.randomUUID();
        UserSummary user = new UserSummary(
                "user-1", List.of("socks"), "access-user", false, null,
                0, 0, 0, "active", Instant.now());
        when(nodeUserService.listUsersForExport(
                nodeId, "user", null, "createdDesc", true))
                .thenReturn(List.of(user));

        mockMvc.perform(get("/api/control/nodes/{nodeId}/users/export", nodeId)
                        .param("keyword", "user")
                        .header("X-Control-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].userId").value("user-1"));

        verify(nodeUserService).listUsersForExport(
                nodeId, "user", null, "createdDesc", true);
    }

    @Test
    void batchConnectionsReturnSecretsWithoutCaching() throws Exception {
        UUID nodeId = UUID.randomUUID();
        UserConnection connection = new UserConnection(
                true, "user-1", UUID.randomUUID().toString(), List.of("socks"), null, null,
                new SocksConnection("203.0.113.20", 5001, "access-user", "access-password"),
                false, Instant.now());
        when(nodeUserService.getConnectionsBatch(nodeId, List.of("user-1")))
                .thenReturn(List.of(new BatchConnectionResult("user-1", connection, null)));

        mockMvc.perform(post("/api/control/nodes/{nodeId}/users/connections/batch", nodeId)
                        .header("X-Control-Token", "admin-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[\"user-1\"]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].userId").value("user-1"))
                .andExpect(jsonPath("$[0].connection.socks.password").value("access-password"));
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
        when(provisioningService.listAllocations(null, true)).thenReturn(List.of(summary));
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

    @Test
    void nodeTokenIsAvailableOnlyToAdminAndNodeOpsAndIsNotCached() throws Exception {
        UUID nodeId = UUID.randomUUID();
        when(managedNodeService.getNodeToken(nodeId))
                .thenReturn(new NodeTokenResponse(nodeId, "node-secret-token"));

        mockMvc.perform(get("/api/control/nodes/{nodeId}/token", nodeId)
                        .header("X-Control-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.nodeId").value(nodeId.toString()))
                .andExpect(jsonPath("$.token").value("node-secret-token"));

        Cookie nodeOps = createRoleAccount("NODE_OPS");
        mockMvc.perform(get("/api/control/nodes/{nodeId}/token", nodeId).cookie(nodeOps))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.token").value("node-secret-token"));

        Cookie provisioner = createRoleAccount("PROVISIONER");
        mockMvc.perform(get("/api/control/nodes/{nodeId}/token", nodeId).cookie(provisioner))
                .andExpect(status().isForbidden());

        Cookie readonly = createRoleAccount("READONLY");
        mockMvc.perform(get("/api/control/nodes/{nodeId}/token", nodeId).cookie(readonly))
                .andExpect(status().isForbidden());
    }

    @Test
    void ordinaryNodeListDoesNotExposeTokenField() throws Exception {
        UUID nodeId = UUID.randomUUID();
        when(managedNodeService.listNodes()).thenReturn(List.of(nodeView(nodeId)));

        mockMvc.perform(get("/api/control/nodes")
                        .header("X-Control-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(nodeId.toString()))
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andExpect(jsonPath("$[0].storedApiToken").doesNotExist());
    }

    @Test
    void readonlyCannotWriteNodesOrReadConnectionSecrets() throws Exception {
        Cookie readonly = createRoleAccount("READONLY");
        mockMvc.perform(get("/api/control/nodes").cookie(readonly))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/control/nodes")
                        .cookie(readonly)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"read-only-node","baseUrl":"http://127.0.0.1:8088","token":"node-token","maxUsers":10}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/control/nodes/{nodeId}/users/{userId}/proxy",
                        UUID.randomUUID(), "user-1").cookie(readonly))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/control/nodes/{nodeId}/users/export", UUID.randomUUID())
                        .cookie(readonly))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/control/nodes/{nodeId}/users/connections/batch", UUID.randomUUID())
                        .cookie(readonly)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[\"user-1\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void provisionerMayProvisionButCannotRegisterNode() throws Exception {
        Cookie provisioner = createRoleAccount("PROVISIONER");
        ProxyProvisionBatchResponse response = new ProxyProvisionBatchResponse(0, 0, 0, List.of());
        when(provisioningService.provisionProxyBatch(any(), any(), any())).thenReturn(response);
        mockMvc.perform(post("/api/control/allocations/proxy-provisions")
                        .cookie(provisioner)
                        .header("Idempotency-Key", "provisioner-rbac")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input":"198.51.100.10 example.test 1080 test-user test-secret","protocols":["socks"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(post("/api/control/nodes")
                        .cookie(provisioner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"provisioner-node","baseUrl":"http://127.0.0.1:8088","token":"node-token","maxUsers":10}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void nodeOpsMayIssueInstallCommandAndDeleteButCannotCreateNodeUser() throws Exception {
        Cookie nodeOps = createRoleAccount("NODE_OPS");
        UUID nodeId = UUID.randomUUID();
        mockMvc.perform(post("/api/control/node-installation").cookie(nodeOps))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.command").exists());
        mockMvc.perform(post("/api/control/nodes/{nodeId}/users", nodeId)
                        .cookie(nodeOps)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"node-user","protocols":["socks"]}
                                """))
                .andExpect(status().isForbidden());

        when(nodeUserService.deleteUser(any(), any(), any(), any()))
                .thenReturn(new OperationResponse(true, "node-user", "deleted"));
        mockMvc.perform(delete("/api/control/nodes/{nodeId}/users/{userId}", nodeId, "node-user")
                        .cookie(nodeOps)
                        .header("Idempotency-Key", "node-ops-delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void auditLogEndpointIsAdminOnly() throws Exception {
        Cookie nodeOps = createRoleAccount("NODE_OPS");
        mockMvc.perform(get("/api/control/audit-logs").cookie(nodeOps))
                .andExpect(status().isForbidden());
        Cookie admin = loginCookie("control-admin", "login-secret");
        mockMvc.perform(get("/api/control/audit-logs").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
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

    private NodeView nodeView(UUID nodeId) {
        return new NodeView(
                nodeId, "Node A", "http://203.0.113.10:8088", "node-a", "online",
                "203.0.113.10", "1.4.1", "1.13.14", "running", true,
                1.0, 2.0, 3, 4, 5, 1080,
                10, 20, 30, Instant.now(), Instant.now(), Instant.now(), null,
                0, true, false, 500, Instant.now());
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

    private Cookie createRoleAccount(String role) throws Exception {
        String username = "rbac-" + role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Cookie admin = loginCookie("control-admin", "login-secret");
        mockMvc.perform(post("/api/control/accounts")
                        .cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username
                                + "\",\"password\":\"rbac-password-123\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(role));
        return loginCookie(username, "rbac-password-123");
    }
}
