package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.client.RemoteNodeException;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ResidentialAllocation;
import com.example.nodecontrol.domain.ResidentialAllocationRepository;
import com.example.nodecontrol.dto.RemoteModels.CreateUserRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.NodeAccessInfo;
import com.example.nodecontrol.dto.RemoteModels.OperationResponse;
import com.example.nodecontrol.dto.RemoteModels.ProxyConfig;
import com.example.nodecontrol.dto.RemoteModels.ProxyMetadataUpdateRequest;
import com.example.nodecontrol.dto.RemoteModels.SocksConnection;
import com.example.nodecontrol.dto.RemoteModels.UpdateUserPolicyRequest;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.UserPage;
import com.example.nodecontrol.dto.RemoteModels.UserPolicyResponse;
import com.example.nodecontrol.dto.RemoteModels.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeUserServiceTest {

    private ManagedNodeService nodeService;
    private NodeManagerClient client;
    private RemoteOperationService operationService;
    private ResidentialAllocationRepository allocationRepository;
    private ProvisioningService provisioningService;
    private IpCountryResolver ipCountryResolver;
    private NodeUserService service;
    private UUID nodeId;
    private ManagedNode node;

    @BeforeEach
    void setUp() {
        nodeService = mock(ManagedNodeService.class);
        client = mock(NodeManagerClient.class);
        operationService = mock(RemoteOperationService.class);
        allocationRepository = mock(ResidentialAllocationRepository.class);
        provisioningService = mock(ProvisioningService.class);
        ipCountryResolver = mock(IpCountryResolver.class);
        service = new NodeUserService(
                nodeService, client, operationService, allocationRepository,
                provisioningService, ipCountryResolver, null);

        nodeId = UUID.randomUUID();
        node = new ManagedNode("Node A", "http://node.example:8088", "encrypted-token");
        node.setId(nodeId);
        when(nodeService.getNode(nodeId)).thenReturn(node);
        when(provisioningService.sharesServer(eq(node), any(ManagedNode.class)))
                .thenAnswer(invocation -> {
                    ManagedNode candidate = invocation.getArgument(1);
                    return candidate != null && nodeId.equals(candidate.getId());
                });
        // Run the actual remote callback so each test exercises the same
        // idempotent delete path used by the production service.
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get())
                .when(operationService)
                .execute(any(), anyString(), anyString(), any(), eq(OperationResponse.class), any());
    }

    @Test
    void missingRemoteUserBy404ReleasesActiveLocalAllocation() {
        ResidentialAllocation allocation = activeAllocation("alice");
        when(allocationRepository.findAllByControlUserIdAndStateIn(
                eq("alice"), any())).thenReturn(List.of(allocation));
        when(client.deleteUser(node, "alice", "delete-404"))
                .thenThrow(new RemoteNodeException(404, "user not found"));

        OperationResponse response = service.deleteUser(nodeId, "alice", "delete-404");

        assertThat(response.success()).isTrue();
        assertThat(response.message()).contains("本地分配记录");
        assertThat(allocation.getState()).isEqualTo("FAILED");
        assertThat(allocation.getNode()).isNull();
        verify(allocationRepository).saveAll(List.of(allocation));
    }

    @Test
    void missingRemoteUserBy409MessageReleasesLocalAllocation() {
        ResidentialAllocation allocation = activeAllocation("alice");
        when(allocationRepository.findAllByControlUserIdAndStateIn(
                eq("alice"), any())).thenReturn(List.of(allocation));
        when(client.deleteUser(node, "alice", "delete-409"))
                .thenThrow(new RemoteNodeException(409, "user does not exist"));

        OperationResponse response = service.deleteUser(nodeId, "alice", "delete-409");

        assertThat(response.success()).isTrue();
        assertThat(allocation.getState()).isEqualTo("FAILED");
        assertThat(allocation.getNode()).isNull();
        verify(allocationRepository).saveAll(List.of(allocation));
    }

    @Test
    void successfulRemoteDeleteReleasesLocalAllocation() {
        ResidentialAllocation allocation = activeAllocation("alice");
        when(allocationRepository.findAllByControlUserIdAndStateIn(
                eq("alice"), any())).thenReturn(List.of(allocation));
        when(client.deleteUser(node, "alice", "delete-ok"))
                .thenReturn(new OperationResponse(true, "alice", "deleted"));

        OperationResponse response = service.deleteUser(nodeId, "alice", "delete-ok");

        assertThat(response.success()).isTrue();
        assertThat(allocation.getState()).isEqualTo("FAILED");
        assertThat(allocation.getNode()).isNull();
        verify(allocationRepository).saveAll(List.of(allocation));
    }

    @Test
    void http200SuccessFalseMissingUserAlsoReleasesLocalAllocation() {
        ResidentialAllocation allocation = activeAllocation("alice");
        when(allocationRepository.findAllByControlUserIdAndStateIn(
                eq("alice"), any())).thenReturn(List.of(allocation));
        when(client.deleteUser(node, "alice", "delete-legacy-missing"))
                .thenReturn(new OperationResponse(false, "alice", "user not found: alice"));

        OperationResponse response = service.deleteUser(nodeId, "alice", "delete-legacy-missing");

        assertThat(response.success()).isTrue();
        assertThat(response.message()).contains("本地分配记录");
        assertThat(allocation.getState()).isEqualTo("FAILED");
        assertThat(allocation.getNode()).isNull();
        verify(allocationRepository).saveAll(List.of(allocation));
    }

    @Test
    void createUserWithResidentialProxyUsesProxyCredentialsForSocks() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get())
                .when(operationService)
                .execute(any(), anyString(), anyString(), any(), eq(CreateUserResponse.class), any());
        CreateUserResponse remoteResponse = new CreateUserResponse(
                true,
                "alice",
                UUID.randomUUID().toString(),
                List.of("socks"),
                null,
                null,
                new SocksConnection("node.example", 5001, "ip-user", "ip-password"),
                true);
        when(client.createUser(eq(node), any(CreateUserRequest.class), eq("create-user")))
                .thenReturn(remoteResponse);
        CreateUserRequest request = new CreateUserRequest(
                "alice",
                List.of("socks"),
                "old-random-user",
                "old-random-password",
                new ProxyConfig(
                        "socks5", "upstream.example", 1080,
                        "ip-user", "ip-password", null, null, null, null, null));

        service.createUser(nodeId, request, "create-user");

        ArgumentCaptor<CreateUserRequest> captor = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(client).createUser(eq(node), captor.capture(), eq("create-user"));
        assertThat(captor.getValue().socksUsername()).isEqualTo("ip-user");
        assertThat(captor.getValue().socksPassword()).isEqualTo("ip-password");
    }

    @Test
    void transientRemoteFailureDoesNotReleaseLocalAllocation() {
        ResidentialAllocation allocation = activeAllocation("alice");
        when(client.deleteUser(node, "alice", "delete-502"))
                .thenThrow(new RemoteNodeException(502, "upstream timeout"));

        assertThatThrownBy(() -> service.deleteUser(nodeId, "alice", "delete-502"))
                .isInstanceOf(RemoteNodeException.class)
                .hasMessageContaining("upstream timeout");

        assertThat(allocation.getState()).isEqualTo("ACTIVE");
        assertThat(allocation.getNode()).isSameAs(node);
        verify(allocationRepository, never()).findAllByControlUserIdAndStateIn(anyString(), any());
    }

    @Test
    void ordinaryConflictOrUnsuccessfulResponseDoesNotReleaseLocalAllocation() {
        ResidentialAllocation allocation = activeAllocation("alice");
        when(client.deleteUser(node, "alice", "delete-conflict"))
                .thenThrow(new RemoteNodeException(409, "user is locked"));

        assertThatThrownBy(() -> service.deleteUser(nodeId, "alice", "delete-conflict"))
                .isInstanceOf(RemoteNodeException.class);
        assertThat(allocation.getState()).isEqualTo("ACTIVE");

        when(client.deleteUser(node, "alice", "delete-false"))
                .thenReturn(new OperationResponse(false, "alice", "delete failed"));
        OperationResponse response = service.deleteUser(nodeId, "alice", "delete-false");

        assertThat(response.success()).isFalse();
        assertThat(allocation.getState()).isEqualTo("ACTIVE");
        verify(allocationRepository, never()).findAllByControlUserIdAndStateIn(anyString(), any());
    }

    @Test
    void successfulDeleteReleasesSameServerAllocationsButKeepsOtherServers() {
        ManagedNode duplicateRegistration = new ManagedNode(
                "Node A duplicate", "http://node-alt.example:8088", "encrypted-token");
        duplicateRegistration.setId(UUID.randomUUID());
        ManagedNode otherServer = new ManagedNode(
                "Node B", "http://node-b.example:8088", "encrypted-token");
        otherServer.setId(UUID.randomUUID());
        ResidentialAllocation selectedAllocation = activeAllocation("alice");
        ResidentialAllocation duplicateAllocation = activeAllocation("alice", duplicateRegistration);
        ResidentialAllocation otherAllocation = activeAllocation("alice", otherServer);
        when(allocationRepository.findAllByControlUserIdAndStateIn(eq("alice"), any()))
                .thenReturn(List.of(selectedAllocation, duplicateAllocation, otherAllocation));
        when(provisioningService.sharesServer(node, duplicateRegistration)).thenReturn(true);
        when(provisioningService.sharesServer(node, otherServer)).thenReturn(false);
        when(client.deleteUser(node, "alice", "delete-same-server"))
                .thenReturn(new OperationResponse(true, "alice", "deleted"));

        service.deleteUser(nodeId, "alice", "delete-same-server");

        assertThat(selectedAllocation.getState()).isEqualTo("FAILED");
        assertThat(duplicateAllocation.getState()).isEqualTo("FAILED");
        assertThat(otherAllocation.getState()).isEqualTo("ACTIVE");
        assertThat(otherAllocation.getNode()).isSameAs(otherServer);
        verify(allocationRepository).saveAll(List.of(selectedAllocation, duplicateAllocation));
    }

    @Test
    void ipSearchEnrichesRemoteUsersAndReturnsOnlyMatchingAccessIp() {
        UserSummary alice = userSummary("alice");
        UserSummary bob = userSummary("bob");
        UserConnection aliceConnection = connection("alice", "203.0.113.10");
        UserConnection bobConnection = connection("bob", "198.51.100.20");
        when(client.getUsers(node, 1, 100, null))
                .thenReturn(new UserPage(List.of(alice, bob), 1, 100, 2));
        when(client.getConnections(node, "alice")).thenReturn(aliceConnection);
        when(client.getConnections(node, "bob")).thenReturn(bobConnection);
        when(provisioningService.accessInfo(aliceConnection, false))
                .thenReturn(new NodeAccessInfo("203.0.113.10", 5001, null, null, "US", "美国", null));
        when(provisioningService.accessInfo(bobConnection, false))
                .thenReturn(new NodeAccessInfo("198.51.100.20", 5002, null, null, "JP", "日本", null));

        UserPage result = service.listUsers(nodeId, 1, 20, null, "198.51.100", false);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).extracting(UserSummary::userId).containsExactly("bob");
        assertThat(result.items().getFirst().access().password()).isNull();
        verify(provisioningService).accessInfo(bobConnection, false);
    }

    @Test
    void ipSearchStopsWhenRemoteManagerReturnsAnEmptyPage() {
        when(client.getUsers(node, 1, 100, null))
                .thenReturn(new UserPage(List.of(userSummary("alice")), 1, 100, 2));
        when(client.getUsers(node, 2, 100, null))
                .thenReturn(new UserPage(List.of(), 2, 100, 2));

        UserPage result = service.listUsers(nodeId, 1, 20, null, "198.51.100", false);

        assertThat(result.items()).isEmpty();
        verify(client, times(2)).getUsers(eq(node), any(Integer.class), eq(100), eq(null));
        verify(client, never()).getUsers(node, 3, 100, null);
    }

    @Test
    void createdDescSortsAcrossRemotePagesBeforeApplyingLocalPagination() {
        Instant oldest = Instant.parse("2026-01-01T00:00:00Z");
        Instant older = Instant.parse("2026-02-01T00:00:00Z");
        Instant newer = Instant.parse("2026-03-01T00:00:00Z");
        Instant newest = Instant.parse("2026-04-01T00:00:00Z");
        when(client.getUsers(node, 1, 100, null)).thenReturn(new UserPage(
                List.of(userSummary("oldest", oldest), userSummary("newest", newest)),
                1, 100, 4));
        when(client.getUsers(node, 2, 100, null)).thenReturn(new UserPage(
                List.of(userSummary("newer", newer), userSummary("older", older)),
                2, 100, 4));

        UserPage result = service.listUsers(
                nodeId, 2, 2, null, null, "createdDesc", false);

        assertThat(result.total()).isEqualTo(4);
        assertThat(result.items()).extracting(UserSummary::userId)
                .containsExactly("older", "oldest");
        verify(client, times(2)).getUsers(eq(node), any(Integer.class), eq(100), eq(null));
    }

    @Test
    void consecutiveLocalPagesReuseTheSameRemoteUserSnapshot() {
        Instant oldest = Instant.parse("2026-01-01T00:00:00Z");
        Instant older = Instant.parse("2026-02-01T00:00:00Z");
        Instant newer = Instant.parse("2026-03-01T00:00:00Z");
        Instant newest = Instant.parse("2026-04-01T00:00:00Z");
        when(client.getUsers(node, 1, 100, null)).thenReturn(new UserPage(
                List.of(userSummary("oldest", oldest), userSummary("newest", newest)),
                1, 100, 4));
        when(client.getUsers(node, 2, 100, null)).thenReturn(new UserPage(
                List.of(userSummary("newer", newer), userSummary("older", older)),
                2, 100, 4));

        UserPage firstPage = service.listUsers(
                nodeId, 1, 2, null, null, "createdDesc", false);
        UserPage secondPage = service.listUsers(
                nodeId, 2, 2, null, null, "createdDesc", false);

        assertThat(firstPage.items()).extracting(UserSummary::userId)
                .containsExactly("newest", "newer");
        assertThat(secondPage.items()).extracting(UserSummary::userId)
                .containsExactly("older", "oldest");
        verify(client, times(2)).getUsers(eq(node), any(Integer.class), eq(100), eq(null));
    }

    @Test
    void forcedRefreshReloadsTheRemoteUserSnapshot() {
        when(client.getUsers(node, 1, 100, null))
                .thenReturn(new UserPage(List.of(userSummary("before")), 1, 100, 1))
                .thenReturn(new UserPage(List.of(userSummary("after")), 1, 100, 1));

        UserPage cached = service.listUsers(
                nodeId, 1, 20, null, null, "createdDesc", false);
        UserPage refreshed = service.listUsers(
                nodeId, 1, 20, null, null, "createdDesc", false, true);

        assertThat(cached.items()).extracting(UserSummary::userId).containsExactly("before");
        assertThat(refreshed.items()).extracting(UserSummary::userId).containsExactly("after");
        verify(client, times(2)).getUsers(node, 1, 100, null);
    }

    @Test
    void successfulPolicyUpdateSyncsAllocationAndInvalidatesUserSnapshot() {
        ResidentialAllocation allocation = activeAllocation("alice");
        allocation.setUserPolicy(1024L, 1);
        UpdateUserPolicyRequest request = new UpdateUserPolicyRequest(4096L, 3);
        UserPolicyResponse response = new UserPolicyResponse(true, "alice", 4096L, 3);
        when(client.getUsers(node, 1, 100, null))
                .thenReturn(new UserPage(List.of(userSummary("before")), 1, 100, 1))
                .thenReturn(new UserPage(List.of(userSummary("after")), 1, 100, 1));
        when(client.updateUserPolicy(node, "alice", request)).thenReturn(response);
        when(allocationRepository.findAllByNodeIdAndControlUserIdAndStateIn(
                eq(nodeId), eq("alice"), any())).thenReturn(List.of(allocation));

        service.listUsers(nodeId, 1, 20, null, null, "createdDesc", false);
        UserPolicyResponse updated = service.updatePolicy(nodeId, "alice", request, null);
        UserPage refreshed = service.listUsers(
                nodeId, 1, 20, null, null, "createdDesc", false);

        assertThat(updated).isSameAs(response);
        assertThat(allocation.getTrafficLimitBytes()).isEqualTo(4096L);
        assertThat(allocation.getMaxSourceIps()).isEqualTo(3);
        assertThat(refreshed.items()).extracting(UserSummary::userId).containsExactly("after");
        verify(allocationRepository).save(allocation);
        verify(client, times(2)).getUsers(node, 1, 100, null);
    }

    @Test
    void unsuccessfulPolicyUpdateDoesNotChangeLocalAllocation() {
        ResidentialAllocation allocation = activeAllocation("alice");
        allocation.setUserPolicy(1024L, 1);
        UpdateUserPolicyRequest request = new UpdateUserPolicyRequest(4096L, 3);
        when(client.updateUserPolicy(node, "alice", request))
                .thenReturn(new UserPolicyResponse(false, "alice", 4096L, 3));

        UserPolicyResponse response = service.updatePolicy(nodeId, "alice", request, null);

        assertThat(response.success()).isFalse();
        assertThat(allocation.getTrafficLimitBytes()).isEqualTo(1024L);
        assertThat(allocation.getMaxSourceIps()).isEqualTo(1);
        verify(allocationRepository, never()).findAllByNodeIdAndControlUserIdAndStateIn(
                any(), anyString(), any());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void successfulDeleteInvalidatesTheRemoteUserSnapshot() {
        when(client.getUsers(node, 1, 100, null))
                .thenReturn(new UserPage(List.of(userSummary("alice")), 1, 100, 1))
                .thenReturn(new UserPage(List.of(), 1, 100, 0));
        when(client.deleteUser(node, "alice", "delete-cached-user"))
                .thenReturn(new OperationResponse(true, "alice", "deleted"));
        when(allocationRepository.findAllByControlUserIdAndStateIn(eq("alice"), any()))
                .thenReturn(List.of());

        UserPage beforeDelete = service.listUsers(
                nodeId, 1, 20, null, null, "createdDesc", false);
        service.deleteUser(nodeId, "alice", "delete-cached-user");
        UserPage afterDelete = service.listUsers(
                nodeId, 1, 20, null, null, "createdDesc", false);

        assertThat(beforeDelete.total()).isEqualTo(1);
        assertThat(afterDelete.total()).isZero();
        verify(client, times(2)).getUsers(node, 1, 100, null);
    }

    @Test
    void createdAscKeepsMissingCreationTimesLast() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-02-01T00:00:00Z");
        when(client.getUsers(node, 1, 100, null)).thenReturn(new UserPage(
                List.of(
                        userSummary("missing", null),
                        userSummary("newer", newer),
                        userSummary("older", older)),
                1, 100, 3));

        UserPage result = service.listUsers(
                nodeId, 1, 3, null, null, "createdAsc", false);

        assertThat(result.items()).extracting(UserSummary::userId)
                .containsExactly("older", "newer", "missing");
    }

    @Test
    void exportUsersScansRemotePagesOnceAndDoesNotReadEveryConnection() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-02-01T00:00:00Z");
        when(client.getUsers(node, 1, 100, null)).thenReturn(new UserPage(
                List.of(userSummary("older", older)), 1, 100, 2));
        when(client.getUsers(node, 2, 100, null)).thenReturn(new UserPage(
                List.of(userSummary("newer", newer)), 2, 100, 2));

        List<UserSummary> result = service.listUsersForExport(
                nodeId, null, null, "createdDesc", true);

        assertThat(result).extracting(UserSummary::userId)
                .containsExactly("newer", "older");
        verify(client, times(2)).getUsers(eq(node), any(Integer.class), eq(100), eq(null));
        verify(client, never()).getConnections(any(), anyString());
    }

    @Test
    void batchConnectionsUseStoredAllocationWithoutCallingRemoteNode() {
        ResidentialAllocation allocation = activeAllocation("stored-user");
        UserConnection storedConnection = connection("stored-user", "203.0.113.30");
        when(allocationRepository.findAllByNodeIdAndControlUserIdInAndStateIn(
                eq(nodeId), any(), eq(List.of("ACTIVE"))))
                .thenReturn(List.of(allocation));
        when(provisioningService.storedConnection(allocation)).thenReturn(storedConnection);

        var result = service.getConnectionsBatch(nodeId, List.of("stored-user"));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().connection()).isSameAs(storedConnection);
        assertThat(result.getFirst().error()).isNull();
        verify(client, never()).getConnections(any(), anyString());
    }

    @Test
    void connectionLookupBackfillsLegacyCountryMetadataAndReturnsRefreshedAliases() {
        UserConnection legacy = countryConnection("legacy-user", "XX", "[XX] 207.152.99.183");
        UserConnection repaired = countryConnection("legacy-user", "US", "[US] 207.152.99.183");
        when(allocationRepository.findAllByNodeIdAndControlUserIdAndStateIn(
                eq(nodeId), eq("legacy-user"), any())).thenReturn(List.of());
        when(client.getConnections(node, "legacy-user")).thenReturn(legacy, repaired);
        when(ipCountryResolver.resolve("207.152.99.183"))
                .thenReturn(new IpCountryResolver.CountryInfo("美国", "US", "洛杉矶"));
        when(client.updateProxyMetadata(eq(node), eq("legacy-user"), any()))
                .thenReturn(new OperationResponse(true, "legacy-user", "updated"));

        UserConnection result = service.getConnections(nodeId, "legacy-user");

        assertThat(result).isSameAs(repaired);
        assertThat(result.protocolsAll().get("vless")).contains("%5BUS%5D");
        ArgumentCaptor<ProxyMetadataUpdateRequest> requestCaptor =
                ArgumentCaptor.forClass(ProxyMetadataUpdateRequest.class);
        verify(client).updateProxyMetadata(eq(node), eq("legacy-user"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().sourceIp()).isEqualTo("207.152.99.183");
        assertThat(requestCaptor.getValue().countryCode()).isEqualTo("US");
        verify(client, times(2)).getConnections(node, "legacy-user");
    }

    @Test
    void connectionLookupKeepsLegacyAliasWhenGeoIpLookupFails() {
        UserConnection legacy = countryConnection("legacy-user", "XX", "[XX] 207.152.99.183");
        when(allocationRepository.findAllByNodeIdAndControlUserIdAndStateIn(
                eq(nodeId), eq("legacy-user"), any())).thenReturn(List.of());
        when(client.getConnections(node, "legacy-user")).thenReturn(legacy);
        when(ipCountryResolver.resolve("207.152.99.183"))
                .thenThrow(new IllegalStateException("geo unavailable"));

        UserConnection result = service.getConnections(nodeId, "legacy-user");

        assertThat(result).isSameAs(legacy);
        verify(client, never()).updateProxyMetadata(any(), anyString(), any());
        verify(client, times(1)).getConnections(node, "legacy-user");
    }

    @Test
    void connectionLookupUsesResolvedCountryWhenMetadataBackfillFails() {
        UserConnection legacy = countryConnection("legacy-user", "XX", "[XX] 207.152.99.183");
        when(allocationRepository.findAllByNodeIdAndControlUserIdAndStateIn(
                eq(nodeId), eq("legacy-user"), any())).thenReturn(List.of());
        when(client.getConnections(node, "legacy-user")).thenReturn(legacy);
        when(ipCountryResolver.resolve("207.152.99.183"))
                .thenReturn(new IpCountryResolver.CountryInfo("美国", "US", "洛杉矶"));
        when(client.updateProxyMetadata(eq(node), eq("legacy-user"), any()))
                .thenThrow(new RemoteNodeException(503, "metadata unavailable"));

        UserConnection result = service.getConnections(nodeId, "legacy-user");

        assertThat(result.protocolInfo())
                .containsEntry("countryCode", "US")
                .containsEntry("countryName", "美国")
                .containsEntry("cityName", "洛杉矶")
                .containsEntry("ip", "207.152.99.183");
        verify(client, times(1)).getConnections(node, "legacy-user");
    }

    @Test
    void connectionLookupOverlaysCountryWhenRefreshedMetadataIsStillLegacy() {
        UserConnection legacy = countryConnection("legacy-user", "XX", "[XX] 207.152.99.183");
        when(allocationRepository.findAllByNodeIdAndControlUserIdAndStateIn(
                eq(nodeId), eq("legacy-user"), any())).thenReturn(List.of());
        when(client.getConnections(node, "legacy-user")).thenReturn(legacy, legacy);
        when(ipCountryResolver.resolve("207.152.99.183"))
                .thenReturn(new IpCountryResolver.CountryInfo("美国", "US", "洛杉矶"));
        when(client.updateProxyMetadata(eq(node), eq("legacy-user"), any()))
                .thenReturn(new OperationResponse(true, "legacy-user", "updated"));

        UserConnection result = service.getConnections(nodeId, "legacy-user");

        assertThat(result.protocolInfo()).containsEntry("countryCode", "US");
        verify(client, times(2)).getConnections(node, "legacy-user");
    }

    @Test
    void connectionLookupSkipsBackfillWhenCountryIsAlreadyValid() {
        UserConnection current = countryConnection("current-user", "US", "[US] 207.152.99.183");
        when(client.getConnections(node, "current-user")).thenReturn(current);

        UserConnection result = service.getConnections(nodeId, "current-user");

        assertThat(result).isSameAs(current);
        verify(ipCountryResolver, never()).resolve(any());
        verify(client, never()).updateProxyMetadata(any(), anyString(), any());
    }

    @Test
    void batchConnectionsFallBackToRemoteAndKeepPerUserErrors() {
        when(allocationRepository.findAllByNodeIdAndControlUserIdInAndStateIn(
                eq(nodeId), any(), eq(List.of("ACTIVE"))))
                .thenReturn(List.of());
        UserConnection remoteConnection = connection("remote-user", "203.0.113.40");
        when(client.getConnections(node, "remote-user")).thenReturn(remoteConnection);
        when(client.getConnections(node, "missing-user"))
                .thenThrow(new RemoteNodeException(404, "user not found"));

        var result = service.getConnectionsBatch(
                nodeId, List.of("remote-user", "missing-user", "remote-user"));

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().connection()).isSameAs(remoteConnection);
        assertThat(result.get(1).connection()).isNull();
        assertThat(result.get(1).error()).contains("user not found");
        verify(client, times(1)).getConnections(node, "remote-user");
        verify(client, times(1)).getConnections(node, "missing-user");
    }

    @Test
    void batchConnectionsRejectMoreThanOneHundredUsers() {
        List<String> userIds = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(index -> "user-" + index)
                .toList();

        assertThatThrownBy(() -> service.getConnectionsBatch(nodeId, userIds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
        verify(nodeService, never()).getNode(any());
    }

    private ResidentialAllocation activeAllocation(String userId) {
        return activeAllocation(userId, node);
    }

    private ResidentialAllocation activeAllocation(String userId, ManagedNode allocationNode) {
        ResidentialAllocation allocation = new ResidentialAllocation(
                "request-" + userId,
                "request-hash",
                userId,
                "remote-key",
                "socks");
        allocation.assignNode(allocationNode);
        allocation.complete(
                new CreateUserResponse(
                        true,
                        userId,
                        "uuid",
                        List.of("socks"),
                        "vless://uuid",
                        "vmess://uuid",
                        new SocksConnection("node.example", 1080, userId, "secret"),
                        false),
                "enc:vless",
                "enc:vmess",
                "enc:user",
                "enc:password");
        return allocation;
    }

    private UserSummary userSummary(String userId) {
        return userSummary(userId, Instant.now());
    }

    private UserSummary userSummary(String userId, Instant createdAt) {
        return new UserSummary(
                userId, List.of("socks"), userId, false, null,
                0, 0, 0, "active", createdAt);
    }

    private UserConnection connection(String userId, String host) {
        return new UserConnection(
                true, userId, UUID.randomUUID().toString(), List.of("socks"), null, null,
                new SocksConnection(host, 5001, userId, "secret"), false, Instant.now());
    }

    private UserConnection countryConnection(String userId, String countryCode, String remark) {
        String encodedRemark = remark.replace("[", "%5B").replace("]", "%5D").replace(" ", "%20");
        return new UserConnection(
                true,
                userId,
                UUID.randomUUID().toString(),
                List.of("vless", "vmess", "socks"),
                "vless://uuid#" + encodedRemark,
                "vmess://payload",
                new SocksConnection("proxy.example", 5001, userId, "secret"),
                true,
                Instant.now(),
                Map.of("vless", "vless://uuid#" + encodedRemark),
                Map.of("sourceIp", "207.152.99.183", "countryCode", countryCode));
    }
}
