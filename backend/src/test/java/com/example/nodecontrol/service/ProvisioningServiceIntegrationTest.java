package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.client.RemoteNodeException;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ManagedNodeRepository;
import com.example.nodecontrol.domain.ResidentialAllocation;
import com.example.nodecontrol.domain.ResidentialAllocationRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.ProvisionRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionRequest;
import com.example.nodecontrol.dto.RemoteModels.AgentHeartbeat;
import com.example.nodecontrol.dto.RemoteModels.AgentInfo;
import com.example.nodecontrol.dto.RemoteModels.CreateUserRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.SocksConnection;
import com.example.nodecontrol.dto.RemoteModels.TrafficTotals;
import com.example.nodecontrol.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:controlplane;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "control-plane.bootstrap.enabled=false",
        "control-plane.security.encryption-key=integration-test-encryption-key"
})
class ProvisioningServiceIntegrationTest {

    @Autowired
    private ProvisioningService provisioningService;

    @Autowired
    private ManagedNodeRepository nodeRepository;

    @Autowired
    private ResidentialAllocationRepository allocationRepository;

    @Autowired
    private SecretCipher secretCipher;

    @MockitoBean
    private NodeManagerClient nodeManagerClient;

    @BeforeEach
    void cleanDatabase() {
        allocationRepository.deleteAll();
        nodeRepository.deleteAll();
        reset(nodeManagerClient);
    }

    @Test
    void transientFailureKeepsNodeAndRemoteIdempotencyKeyThenRetriesDirectly() {
        ManagedNode node = saveOnlineNode("node-a", 10);
        when(nodeManagerClient.createUser(any(), any(), any()))
                .thenThrow(new RemoteNodeException(502, "timeout"))
                .thenReturn(successResponse("customer-1"));

        ProvisionRequest request = new ProvisionRequest(
                "customer-1", List.of("vless", "vmess", "socks"), null);

        assertThatThrownBy(() -> provisioningService.provision("order-1", request))
                .isInstanceOf(RemoteNodeException.class);

        ResidentialAllocation retryable = allocationRepository.findByRequestKey("order-1").orElseThrow();
        UUID originalNodeId = retryable.getNode().getId();
        String remoteKey = retryable.getRemoteIdempotencyKey();
        assertThat(retryable.getState()).isEqualTo("RETRYABLE");
        assertThat(originalNodeId).isEqualTo(node.getId());

        var completed = provisioningService.retry(retryable.getId());

        assertThat(completed.state()).isEqualTo("ACTIVE");
        assertThat(completed.nodeId()).isEqualTo(originalNodeId);
        assertThat(completed.connection().proxyBound()).isFalse();
        assertThat(completed.connection().socks().password()).isEqualTo("local-password");

        ArgumentCaptor<CreateUserRequest> requestCaptor = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(nodeManagerClient, times(2)).createUser(
                org.mockito.ArgumentMatchers.argThat(value -> value.getId().equals(originalNodeId)),
                requestCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(remoteKey));
        assertDirectRequest(requestCaptor.getAllValues().getFirst());
        assertDirectRequest(requestCaptor.getAllValues().getLast());
    }

    @Test
    void definitiveRemoteFailureReleasesTheSelectedNode() {
        saveOnlineNode("node-a", 10);
        when(nodeManagerClient.createUser(any(), any(), any()))
                .thenThrow(new RemoteNodeException(400, "invalid request"));

        ProvisionRequest request = new ProvisionRequest(
                "customer-2", List.of("socks"), null);

        assertThatThrownBy(() -> provisioningService.provision("order-2", request))
                .isInstanceOf(RemoteNodeException.class);

        ResidentialAllocation failed = allocationRepository.findByRequestKey("order-2").orElseThrow();
        assertThat(failed.getState()).isEqualTo("FAILED");
        assertThat(failed.getNode()).isNull();
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentRequest() {
        saveOnlineNode("node-a", 10);
        when(nodeManagerClient.createUser(any(), any(), any())).thenReturn(successResponse("customer-3"));

        provisioningService.provision("order-3", new ProvisionRequest(
                "customer-3", List.of("socks"), null));

        assertThatThrownBy(() -> provisioningService.provision("order-3", new ProvisionRequest(
                "customer-4", List.of("socks"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void idempotentReplayReturnsActiveAllocationWithoutCreatingAnotherRemoteUser() {
        saveOnlineNode("node-a", 10);
        when(nodeManagerClient.createUser(any(), any(), any())).thenReturn(successResponse("customer-5"));

        ProvisionRequest request = new ProvisionRequest(
                "customer-5", List.of("vless", "vmess", "socks"), null);

        var created = provisioningService.provision("order-5", request);
        var replayed = provisioningService.provision("order-5", request);

        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(replayed.state()).isEqualTo("ACTIVE");
        assertThat(replayed.nodeId()).isEqualTo(created.nodeId());
        assertThat(replayed.connection().uuid()).isEqualTo(created.connection().uuid());
        assertThat(replayed.connection().proxyBound()).isFalse();

        ArgumentCaptor<CreateUserRequest> requestCaptor = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(nodeManagerClient, times(1)).createUser(any(), requestCaptor.capture(), any());
        assertDirectRequest(requestCaptor.getValue());
    }

    @Test
    void proxyBatchAcceptsFiveAndSixColumnsAndCleansSpreadsheetWhitespace() {
        saveOnlineNode("node-a", 10);
        when(nodeManagerClient.createUser(any(), any(), any())).thenAnswer(invocation -> {
            CreateUserRequest request = invocation.getArgument(1);
            return successResponse(request.userId());
        });
        String input = "\uFEFF198.51.100.10\u00A0edge-a.example\t1080\tuser-a\tsecret-a\n"
                + "2\u3000198.51.100.11\u3000-\u30001081\u3000user-b\u3000secret-b";

        var response = provisioningService.provisionProxyBatch(
                "batch-spreadsheet",
                new ProxyProvisionRequest(input, List.of("vless", "socks"), null, "client"));

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.succeeded()).isEqualTo(2);
        assertThat(response.failed()).isZero();
        assertThat(response.results()).extracting(result -> result.rowNumber()).containsExactly(1, 2);
        assertThat(response.results()).extracting(result -> result.sourceAddress())
                .containsExactly("edge-a.example", "198.51.100.11");

        ArgumentCaptor<CreateUserRequest> requestCaptor = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(nodeManagerClient, times(2)).createUser(any(), requestCaptor.capture(), any());
        assertThat(requestCaptor.getAllValues().get(0).proxy().server()).isEqualTo("edge-a.example");
        assertThat(requestCaptor.getAllValues().get(0).proxy().port()).isEqualTo(1080);
        assertThat(requestCaptor.getAllValues().get(0).proxy().username()).isEqualTo("user-a");
        assertThat(requestCaptor.getAllValues().get(0).proxy().password()).isEqualTo("secret-a");
        assertThat(requestCaptor.getAllValues().get(1).proxy().server()).isEqualTo("198.51.100.11");
        assertThat(requestCaptor.getAllValues().get(1).proxy().port()).isEqualTo(1081);

        List<ResidentialAllocation> stored = allocationRepository.findAll();
        assertThat(stored).hasSize(2).allSatisfy(allocation -> {
            assertThat(allocation.getProvisioningMode()).isEqualTo("UPSTREAM_SOCKS");
            assertThat(allocation.getProxyUsernameCipher()).startsWith("enc:v1:");
            assertThat(allocation.getProxyPasswordCipher()).startsWith("enc:v1:");
            assertThat(allocation.getProxyPasswordCipher()).doesNotContain("secret-");
        });
    }

    @Test
    void proxyBatchKeepsValidRowsWhenOtherRowsAreInvalidAndPreservesSourceOrder() {
        saveOnlineNode("node-a", 10);
        when(nodeManagerClient.createUser(any(), any(), any())).thenAnswer(invocation -> {
            CreateUserRequest request = invocation.getArgument(1);
            return successResponse(request.userId());
        });
        String input = "198.51.100.10 edge-a.example 1080 user-a secret-a\n"
                + "999.51.100.10 broken.example 70000 invalid-user invalid-secret\n"
                + "3 198.51.100.12 edge-c.example 1082 user-c secret-c";

        var response = provisioningService.provisionProxyBatch(
                "batch-mixed",
                new ProxyProvisionRequest(input, List.of("socks"), null, "mixed"));

        assertThat(response.total()).isEqualTo(3);
        assertThat(response.succeeded()).isEqualTo(2);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results()).extracting(result -> result.rowNumber()).containsExactly(1, 2, 3);
        assertThat(response.results().get(1).allocation()).isNull();
        assertThat(response.results().get(1).error())
                .contains("第 2 行")
                .doesNotContain("invalid-user")
                .doesNotContain("invalid-secret");
        verify(nodeManagerClient, times(2)).createUser(any(), any(), any());
    }

    @Test
    void proxyBatchReportsInvalidDomainPortAndColumnCountWithoutCallingNodeManager() {
        String input = "198.51.100.10 bad_domain 1080 user-a secret-a\n"
                + "198.51.100.11 example.com not-a-port user-b secret-b\n"
                + "198.51.100.12 example.com 1080 missing-password";

        var response = provisioningService.provisionProxyBatch(
                "batch-invalid",
                new ProxyProvisionRequest(input, List.of("socks"), null, "invalid"));

        assertThat(response.total()).isEqualTo(3);
        assertThat(response.succeeded()).isZero();
        assertThat(response.failed()).isEqualTo(3);
        assertThat(response.results()).extracting(result -> result.rowNumber()).containsExactly(1, 2, 3);
        assertThat(response.results()).extracting(result -> result.error())
                .allSatisfy(message -> assertThat(message).contains("第 "));
        assertThat(response.results().get(0).error()).contains("域名格式不正确");
        assertThat(response.results().get(1).error()).contains("端口不是有效数字");
        assertThat(response.results().get(2).error()).contains("5 列或带序号的 6 列");
        verify(nodeManagerClient, never()).createUser(any(), any(), any());
    }

    @Test
    void proxyBatchRedactsUpstreamCredentialsFromRemoteErrors() {
        saveOnlineNode("node-a", 10);
        when(nodeManagerClient.createUser(any(), any(), any()))
                .thenThrow(new RemoteNodeException(
                        502, "proxy-user and proxy-password were rejected"));

        var response = provisioningService.provisionProxyBatch(
                "batch-redaction",
                new ProxyProvisionRequest(
                        "198.51.100.10 edge.example 1080 proxy-user proxy-password",
                        List.of("socks"), null, "redacted"));

        assertThat(response.succeeded()).isZero();
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results().getFirst().error())
                .contains("***")
                .doesNotContain("proxy-user")
                .doesNotContain("proxy-password");
        ResidentialAllocation stored = allocationRepository.findAll().getFirst();
        assertThat(stored.getLastError())
                .doesNotContain("proxy-user")
                .doesNotContain("proxy-password");
    }

    private void assertDirectRequest(CreateUserRequest request) {
        assertThat(request.socksUsername()).isNull();
        assertThat(request.socksPassword()).isNull();
        assertThat(request.proxy()).isNull();
    }

    private ManagedNode saveOnlineNode(String remoteNodeId, int maxUsers) {
        ManagedNode node = new ManagedNode(
                "Node " + remoteNodeId,
                "http://" + remoteNodeId + ".example:8088",
                secretCipher.encrypt("node-token"));
        node.updateRegistration(
                node.getName(),
                node.getBaseUrl(),
                node.getStoredApiToken(),
                new AgentInfo("node-manager", "v1", "1.4.1", remoteNodeId, List.of(), List.of(),
                        "Idempotency-Key", "/api/agent/heartbeat"),
                maxUsers);
        node.setMaxUsers(maxUsers);
        node.recordHeartbeat(new AgentHeartbeat(
                remoteNodeId,
                node.getName(),
                "203.0.113.10",
                "online",
                "1.4.1",
                "1.13.14",
                "running",
                true,
                10,
                20,
                0,
                5,
                0,
                new TrafficTotals(0, 0, 0, true, "test", Instant.now()),
                Instant.now()));
        return nodeRepository.saveAndFlush(node);
    }

    private CreateUserResponse successResponse(String userId) {
        return new CreateUserResponse(
                true,
                userId,
                UUID.randomUUID().toString(),
                List.of("vless", "vmess", "socks"),
                "vless://generated",
                "vmess://generated",
                new SocksConnection("203.0.113.10", 5001, userId, "local-password"),
                false);
    }
}
