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
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.UserPage;
import com.example.nodecontrol.dto.RemoteModels.UserSummary;
import com.example.nodecontrol.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @MockitoBean
    private IpCountryResolver ipCountryResolver;

    @MockitoBean
    private HostAddressResolver hostAddressResolver;

    @BeforeEach
    void cleanDatabase() {
        allocationRepository.deleteAll();
        nodeRepository.deleteAll();
        reset(nodeManagerClient, ipCountryResolver, hostAddressResolver);
        when(ipCountryResolver.resolve(any())).thenReturn(IpCountryResolver.UNKNOWN);
        when(hostAddressResolver.resolve(any())).thenReturn(Set.of());
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
            return residentialSuccessResponse(request.userId());
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
        assertThat(response.results()).extracting(result -> result.sourceIp())
                .containsExactly("198.51.100.10", "198.51.100.11");
        assertThat(response.results()).extracting(result -> result.sourceAddress())
                .containsExactly("edge-a.example", "198.51.100.11");

        ArgumentCaptor<CreateUserRequest> requestCaptor = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(nodeManagerClient, times(2)).createUser(any(), requestCaptor.capture(), any());
        assertThat(requestCaptor.getAllValues().get(0).proxy().server()).isEqualTo("edge-a.example");
        assertThat(requestCaptor.getAllValues().get(0).proxy().port()).isEqualTo(1080);
        assertThat(requestCaptor.getAllValues().get(0).proxy().username()).isEqualTo("user-a");
        assertThat(requestCaptor.getAllValues().get(0).proxy().password()).isEqualTo("secret-a");
        assertThat(requestCaptor.getAllValues().get(0).userId()).isEqualTo("user-a");
        assertThat(requestCaptor.getAllValues().get(0).protocols())
                .containsExactly("vless", "vmess", "socks");
        assertThat(requestCaptor.getAllValues().get(1).proxy().server()).isEqualTo("198.51.100.11");
        assertThat(requestCaptor.getAllValues().get(1).proxy().port()).isEqualTo(1081);
        assertThat(requestCaptor.getAllValues().get(1).userId()).isEqualTo("user-b");

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
            return residentialSuccessResponse(request.userId());
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
    void proxyBatchCreatesThreeProtocolResidentialRouteFromExitIpAndSocksEntryAddress() {
        saveOnlineNode("node-a", 10);
        when(ipCountryResolver.resolve("203.0.113.10"))
                .thenReturn(new IpCountryResolver.CountryInfo("美国", "US"));
        when(nodeManagerClient.createUser(any(), any(), any())).thenAnswer(invocation -> {
            CreateUserRequest request = invocation.getArgument(1);
            return residentialSuccessResponse(request.userId());
        });

        var response = provisioningService.provisionProxyBatch(
                "batch-residential-route",
                new ProxyProvisionRequest(
                        "203.0.113.10\t198.51.100.20\t5001\tresidential-test-user\tresidential-test-password",
                        List.of("socks"), null, "residential"));

        assertThat(response.succeeded()).isEqualTo(1);
        assertThat(response.failed()).isZero();
        var result = response.results().getFirst();
        assertThat(result.sourceIp()).isEqualTo("203.0.113.10");
        assertThat(result.sourceDomain()).isEqualTo("198.51.100.20");
        assertThat(result.sourceAddress()).isEqualTo("198.51.100.20");
        assertThat(result.sourcePort()).isEqualTo(5001);
        assertThat(result.allocation().userId()).isEqualTo("residential-test-user");
        assertThat(result.countryName()).isEqualTo("美国");
        assertThat(result.countryCode()).isEqualTo("US");
        // The batch response must not expose an upstream SOCKS URI.  The
        // generated node-user SOCKS entry is returned under allocation.connection,
        // alongside VLESS and VMess, just like manual user creation.
        assertThat(result.socksLink()).isNull();
        assertThat(result.error()).isNull();
        assertThat(result.allocation().protocols()).containsExactly("vless", "vmess", "socks");
        assertThat(result.allocation().proxyBound()).isTrue();
        assertThat(result.allocation().connection().proxyBound()).isTrue();
        assertThat(result.allocation().connection().vless()).startsWith("vless://");
        assertThat(result.allocation().connection().vmess()).startsWith("vmess://");
        assertThat(result.allocation().connection().socks()).isNotNull();
        assertThat(result.allocation().proxyUsername()).isNull();
        assertThat(result.allocation().proxyPassword()).isNull();

        ArgumentCaptor<CreateUserRequest> requestCaptor = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(nodeManagerClient).createUser(any(), requestCaptor.capture(), any());
        CreateUserRequest remoteRequest = requestCaptor.getValue();
        assertThat(remoteRequest.protocols()).containsExactly("vless", "vmess", "socks");
        assertThat(remoteRequest.proxy().server()).isEqualTo("198.51.100.20");
        assertThat(remoteRequest.proxy().port()).isEqualTo(5001);
        assertThat(remoteRequest.proxy().username()).isEqualTo("residential-test-user");
        assertThat(remoteRequest.proxy().password()).isEqualTo("residential-test-password");

        ResidentialAllocation stored = allocationRepository.findAll().getFirst();
        assertThat(stored.getProxySourceIp()).isEqualTo("203.0.113.10");
        assertThat(stored.getProxySourceDomain()).isEqualTo("198.51.100.20");
        assertThat(stored.getProxyServer()).isEqualTo("198.51.100.20");
        assertThat(stored.getProxyPasswordCipher())
                .startsWith("enc:v1:")
                .doesNotContain("test-password");
    }

    @Test
    void geoIpFailureFallsBackWithoutBlockingResidentialProvisioning() {
        saveOnlineNode("node-a", 10);
        when(ipCountryResolver.resolve("198.51.100.88"))
                .thenThrow(new IllegalStateException("geo service unavailable"));
        when(nodeManagerClient.createUser(any(), any(), any())).thenAnswer(invocation -> {
            CreateUserRequest request = invocation.getArgument(1);
            return residentialSuccessResponse(request.userId());
        });

        var response = provisioningService.provisionProxyBatch(
                "batch-geo-fallback",
                new ProxyProvisionRequest(
                        "198.51.100.88 edge.example 1080 geo-user geo-password",
                        List.of("socks"), null, "geo"));

        assertThat(response.succeeded()).isEqualTo(1);
        assertThat(response.failed()).isZero();
        assertThat(response.results().getFirst().countryName()).isEqualTo("未知");
        assertThat(response.results().getFirst().countryCode()).isEqualTo("ZZ");
    }

    @Test
    void proxyDomainResolvingToPreferredNodeIsRejectedToPreventLoop() {
        ManagedNode node = saveOnlineNodeAtHost("node-proxy-loop", 10, "198.51.100.50");
        when(hostAddressResolver.resolve("upstream.example"))
                .thenReturn(Set.of("198.51.100.50"));

        var response = provisioningService.provisionProxyBatch(
                "batch-proxy-loop",
                new ProxyProvisionRequest(
                        "38.30.216.149 upstream.example 5001 loop-user loop-password",
                        List.of("socks"), node.getId(), "loop"));

        assertThat(response.succeeded()).isZero();
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results().getFirst().error())
                .contains("上游 SOCKS 地址相同")
                .contains("代理回环")
                .doesNotContain("loop-password");
        verify(nodeManagerClient, never()).createUser(any(), any(), any());
    }

    @Test
    void sameServerDifferentUpstreamSocksPortIsAllowed() {
        ManagedNode node = saveOnlineNodeAtHost("node-independent-socks", 10, "198.51.100.50");
        node.recordHeartbeat(new AgentHeartbeat(
                node.getRemoteNodeId(), node.getName(), node.getHost(), "online",
                node.getManagerVersion(), node.getSingboxVersion(), node.getSingbox(),
                true, 10, 20, 0, 5, 0, 6000,
                new TrafficTotals(0, 0, 0, true, "test", Instant.now()), Instant.now()));
        nodeRepository.saveAndFlush(node);
        when(nodeManagerClient.createUser(any(), any(), any())).thenAnswer(invocation -> {
            CreateUserRequest request = invocation.getArgument(1);
            return residentialSuccessResponse(request.userId());
        });

        var response = provisioningService.provisionProxyBatch(
                "batch-independent-socks",
                new ProxyProvisionRequest(
                        "38.30.216.149 198.51.100.50 5001 independent-user secret",
                        List.of("socks"), node.getId(), "independent"));

        assertThat(response.succeeded()).isEqualTo(1);
        verify(nodeManagerClient).createUser(any(), any(), any());
    }

    @Test
    void proxyBatchRejectsAResponseThatIsNotBoundToAllThreeProtocols() {
        saveOnlineNode("node-a", 10);
        when(nodeManagerClient.createUser(any(), any(), any()))
                .thenReturn(successResponse("incomplete-residential"));

        var response = provisioningService.provisionProxyBatch(
                "batch-incomplete-residential",
                new ProxyProvisionRequest(
                        "198.51.100.20 203.0.113.20 1080 test-user test-password",
                        List.of("socks"), null, "incomplete"));

        assertThat(response.succeeded()).isZero();
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results().getFirst().error())
                .contains("原生住宅出口")
                .doesNotContain("test-user")
                .doesNotContain("test-password");
        assertThat(allocationRepository.findAll().getFirst().getState()).isEqualTo("RETRYABLE");
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
        assertThat(response.results().get(0).error()).contains("SOCKS 接入地址格式不正确");
        assertThat(response.results().get(1).error()).contains("端口不是有效数字");
        assertThat(response.results().get(2).error()).contains("5 列或带序号的 6 列");
        verify(nodeManagerClient, never()).createUser(any(), any(), any());
    }

    @Test
    void directProvisionUsesDeterministicDefaultWhenUserIdIsMissing() {
        saveOnlineNode("node-default", 10);
        when(nodeManagerClient.createUser(any(), any(), any())).thenReturn(successResponse("node-default-user"));

        var result = provisioningService.provision("default-user-request", new ProvisionRequest(
                "", List.of("socks"), null));

        assertThat(result.userId()).startsWith("node-").hasSize(21);
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

    @Test
    void sameUserIdIsRejectedOnlyOnTheSameNode() {
        ManagedNode firstNode = saveOnlineNode("node-scope-a", 10);
        ManagedNode secondNode = saveOnlineNode("node-scope-b", 10);
        assertThat(firstNode.getHost()).isNotEqualTo(secondNode.getHost());
        when(nodeManagerClient.createUser(any(), any(), any())).thenAnswer(invocation -> {
            CreateUserRequest request = invocation.getArgument(1);
            return residentialSuccessResponse(request.userId());
        });
        when(nodeManagerClient.getConnections(any(), any()))
                .thenReturn(new UserConnection(
                        true,
                        "shared-user",
                        UUID.randomUUID().toString(),
                        List.of("vless", "vmess", "socks"),
                        "vless://existing",
                        "vmess://existing",
                        new SocksConnection("203.0.113.10", 5001, "shared-user", "remote-password"),
                        true,
                        Instant.now()));

        var first = provisioningService.provisionProxyBatch(
                "batch-scope-a",
                new ProxyProvisionRequest(
                        "198.51.100.31 edge-a.example 1080 shared-user secret-a",
                        List.of("socks"), firstNode.getId(), "scope"));
        assertThat(first.succeeded()).isEqualTo(1);

        var sameNode = provisioningService.provisionProxyBatch(
                "batch-scope-same-node",
                new ProxyProvisionRequest(
                        "198.51.100.32 edge-b.example 1081 shared-user secret-b",
                        List.of("socks"), firstNode.getId(), "scope"));
        assertThat(sameNode.succeeded()).isZero();
        assertThat(sameNode.results().getFirst().error())
                .contains("Node node-scope-a");

        var differentNode = provisioningService.provisionProxyBatch(
                "batch-scope-b",
                new ProxyProvisionRequest(
                        "198.51.100.33 edge-c.example 1082 shared-user secret-c",
                        List.of("socks"), secondNode.getId(), "scope"));
        assertThat(differentNode.succeeded()).isEqualTo(1);
    }

    @Test
    void sameUserIdIsRejectedAcrossDifferentNodeRecordsWithTheSameServerIp() {
        ManagedNode firstNode = saveOnlineNodeAtHost("node-ip-a", 10, "198.51.100.10");
        ManagedNode secondNode = saveOnlineNodeAtHost("node-ip-b", 10, "198.51.100.10");
        assertThat(firstNode.getId()).isNotEqualTo(secondNode.getId());
        assertThat(firstNode.getHost()).isEqualTo(secondNode.getHost());
        when(nodeManagerClient.createUser(any(), any(), any())).thenAnswer(invocation -> {
            CreateUserRequest request = invocation.getArgument(1);
            return residentialSuccessResponse(request.userId());
        });
        when(nodeManagerClient.getConnections(any(), any()))
                .thenReturn(new UserConnection(
                        true,
                        "same-server-user",
                        UUID.randomUUID().toString(),
                        List.of("vless", "vmess", "socks"),
                        "vless://existing",
                        "vmess://existing",
                        new SocksConnection("198.51.100.10", 5001, "same-server-user", "remote-password"),
                        true,
                        Instant.now()));

        var first = provisioningService.provisionProxyBatch(
                "batch-same-server-first",
                new ProxyProvisionRequest(
                        "198.51.100.61 edge-a.example 1080 same-server-user secret-a",
                        List.of("socks"), firstNode.getId(), "same-server"));
        assertThat(first.succeeded()).isEqualTo(1);

        var second = provisioningService.provisionProxyBatch(
                "batch-same-server-second",
                new ProxyProvisionRequest(
                        "198.51.100.62 edge-b.example 1081 same-server-user secret-b",
                        List.of("socks"), secondNode.getId(), "same-server"));
        assertThat(second.succeeded()).isZero();
        assertThat(second.results().getFirst().error())
                .contains("节点用户 ID 已存在于节点");
        verify(nodeManagerClient, times(1)).createUser(any(), any(), any());
    }

    @Test
    void existingRemoteUserOnSameServerIpBlocksNewAllocationEvenWithoutLocalHistory() {
        ManagedNode node = saveOnlineNodeAtHost("node-remote-existing", 10, "198.51.100.90");
        when(nodeManagerClient.getUsers(any(), any(Integer.class), any(Integer.class), any()))
                .thenReturn(new UserPage(
                        List.of(new UserSummary(
                                "existing-user",
                                List.of("socks"),
                                null,
                                false,
                                null,
                                0,
                                0,
                                0,
                                "active",
                                Instant.now())),
                        1,
                        100,
                        1));

        var response = provisioningService.provisionProxyBatch(
                "batch-remote-existing",
                new ProxyProvisionRequest(
                        "198.51.100.91 edge.example 1080 existing-user secret",
                        List.of("socks"), node.getId(), "remote-existing"));

        assertThat(response.succeeded()).isZero();
        assertThat(response.results().getFirst().error())
                .contains("当前服务器的节点用户");
        verify(nodeManagerClient, never()).createUser(any(), any(), any());
    }

    @Test
    void existingRemoteUserOnDuplicateRegistrationOfSameServerAlsoBlocksNewAllocation() {
        ManagedNode selectedNode = saveOnlineNodeAtHost("node-duplicate-registration-a", 10, "198.51.100.91");
        ManagedNode duplicateRegistration = saveOnlineNodeAtHost(
                "node-duplicate-registration-b", 10, "198.51.100.91");
        UserPage remotePage = new UserPage(
                List.of(new UserSummary(
                        "duplicate-user",
                        List.of("socks"),
                        null,
                        false,
                        null,
                        0,
                        0,
                        0,
                        "active",
                        Instant.now())),
                1,
                100,
                1);
        when(nodeManagerClient.getUsers(any(), any(Integer.class), any(Integer.class), eq("duplicate-user")))
                .thenAnswer(invocation -> {
                    ManagedNode queried = invocation.getArgument(0);
                    return queried.getBaseUrl().contains("duplicate-registration-b") ? remotePage : null;
                });

        var response = provisioningService.provisionProxyBatch(
                "batch-duplicate-registration",
                new ProxyProvisionRequest(
                        "198.51.100.92 edge.example 1080 duplicate-user secret",
                        List.of("socks"), selectedNode.getId(), "duplicate-registration"));

        assertThat(response.succeeded()).isZero();
        assertThat(response.results().getFirst().error())
                .contains("当前服务器的节点用户");
        verify(nodeManagerClient, never()).createUser(any(), any(), any());
    }

    @Test
    void fallsBackToNodeManagerBaseUrlWhenHeartbeatDoesNotReportServerIp() {
        ManagedNode firstNode = saveOnlineNodeAtHost("node-fallback-a", 10, null);
        ManagedNode secondNode = saveOnlineNodeAtHost("node-fallback-b", 10, null);
        firstNode.setBaseUrl("http://198.51.100.20:8088");
        secondNode.setBaseUrl("http://198.51.100.20:9090");
        nodeRepository.saveAllAndFlush(List.of(firstNode, secondNode));
        when(nodeManagerClient.createUser(any(), any(), any())).thenAnswer(invocation -> {
            CreateUserRequest request = invocation.getArgument(1);
            return residentialSuccessResponse(request.userId());
        });
        when(nodeManagerClient.getConnections(any(), any()))
                .thenReturn(new UserConnection(
                        true,
                        "fallback-user",
                        UUID.randomUUID().toString(),
                        List.of("vless", "vmess", "socks"),
                        "vless://existing",
                        "vmess://existing",
                        new SocksConnection("198.51.100.20", 5001, "fallback-user", "remote-password"),
                        true,
                        Instant.now()));

        var first = provisioningService.provisionProxyBatch(
                "batch-fallback-first",
                new ProxyProvisionRequest(
                        "198.51.100.71 edge-a.example 1080 fallback-user secret-a",
                        List.of("socks"), firstNode.getId(), "fallback"));
        assertThat(first.succeeded()).isEqualTo(1);

        var second = provisioningService.provisionProxyBatch(
                "batch-fallback-second",
                new ProxyProvisionRequest(
                        "198.51.100.72 edge-b.example 1081 fallback-user secret-b",
                        List.of("socks"), secondNode.getId(), "fallback"));
        assertThat(second.succeeded()).isZero();
        assertThat(second.results().getFirst().error())
                .contains("节点用户 ID 已存在于节点");
    }

    @Test
    void refusesToCreateWhenNodeServerIdentityCannotBeDetermined() {
        ManagedNode node = saveOnlineNodeAtHost("node-no-identity", 10, null);
        node.setBaseUrl("http://");
        nodeRepository.saveAndFlush(node);

        var response = provisioningService.provisionProxyBatch(
                "batch-no-identity",
                new ProxyProvisionRequest(
                        "198.51.100.80 edge.example 1080 no-identity secret",
                        List.of("socks"), node.getId(), "identity"));

        assertThat(response.succeeded()).isZero();
        assertThat(response.results().getFirst().error())
                .contains("无法识别节点服务器 IP");
        verify(nodeManagerClient, never()).createUser(any(), any(), any());
    }

    @Test
    void detachedHistoryFromDeletedNodeDoesNotBlockReuseOnAnotherNode() {
        ManagedNode oldNode = saveOnlineNode("node-deleted", 10);
        ManagedNode newNode = saveOnlineNode("node-after-delete", 10);
        when(nodeManagerClient.createUser(any(), any(), any())).thenAnswer(invocation -> {
            CreateUserRequest request = invocation.getArgument(1);
            return residentialSuccessResponse(request.userId());
        });

        var created = provisioningService.provisionProxyBatch(
                "batch-before-delete",
                new ProxyProvisionRequest(
                        "198.51.100.41 edge-old.example 1080 reused-user secret-a",
                        List.of("socks"), oldNode.getId(), "delete"));
        assertThat(created.succeeded()).isEqualTo(1);
        ResidentialAllocation history = allocationRepository.findAll().getFirst();
        history.fail("removed with node", true);
        history.detachNode();
        allocationRepository.saveAndFlush(history);

        var reused = provisioningService.provisionProxyBatch(
                "batch-after-delete",
                new ProxyProvisionRequest(
                        "198.51.100.42 edge-new.example 1081 reused-user secret-b",
                        List.of("socks"), newNode.getId(), "delete"));
        assertThat(reused.succeeded()).isEqualTo(1);
    }

    @Test
    void deletedRemoteUserReleasesStaleAllocationBeforeCreatingAgain() {
        ManagedNode node = saveOnlineNode("node-remote-deleted", 10);
        when(nodeManagerClient.createUser(any(), any(), any())).thenAnswer(invocation -> {
            CreateUserRequest request = invocation.getArgument(1);
            return residentialSuccessResponse(request.userId());
        });
        when(nodeManagerClient.getConnections(any(), any()))
                .thenThrow(new RemoteNodeException(409, "user not found"));

        var first = provisioningService.provisionProxyBatch(
                "batch-remote-deleted-first",
                new ProxyProvisionRequest(
                        "198.51.100.51 edge-old.example 1080 reused-user secret-a",
                        List.of("socks"), node.getId(), "remote-deleted"));
        assertThat(first.succeeded()).isEqualTo(1);

        var second = provisioningService.provisionProxyBatch(
                "batch-remote-deleted-second",
                new ProxyProvisionRequest(
                        "198.51.100.52 edge-new.example 1081 reused-user secret-b",
                        List.of("socks"), node.getId(), "remote-deleted"));
        assertThat(second.succeeded()).isEqualTo(1);
        assertThat(second.results().getFirst().error()).isNull();
        assertThat(allocationRepository.findAll())
                .extracting(ResidentialAllocation::getState)
                .containsExactlyInAnyOrder("FAILED", "ACTIVE");
    }

    private void assertDirectRequest(CreateUserRequest request) {
        assertThat(request.socksUsername()).isNull();
        assertThat(request.socksPassword()).isNull();
        assertThat(request.proxy()).isNull();
    }

    private ManagedNode saveOnlineNode(String remoteNodeId, int maxUsers) {
        return saveOnlineNodeAtHost(
                remoteNodeId,
                maxUsers,
                remoteNodeId.endsWith("b") ? "203.0.113.11" : "203.0.113.10");
    }

    private ManagedNode saveOnlineNodeAtHost(String remoteNodeId, int maxUsers, String host) {
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
                host,
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

    private CreateUserResponse residentialSuccessResponse(String userId) {
        return new CreateUserResponse(
                true,
                userId,
                UUID.randomUUID().toString(),
                List.of("vless", "vmess", "socks"),
                "vless://generated",
                "vmess://generated",
                new SocksConnection("203.0.113.10", 5001, userId, "local-password"),
                true);
    }
}
