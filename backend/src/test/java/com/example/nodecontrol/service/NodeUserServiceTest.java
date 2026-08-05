package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.client.RemoteNodeException;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ResidentialAllocation;
import com.example.nodecontrol.domain.ResidentialAllocationRepository;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.OperationResponse;
import com.example.nodecontrol.dto.RemoteModels.SocksConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeUserServiceTest {

    private ManagedNodeService nodeService;
    private NodeManagerClient client;
    private RemoteOperationService operationService;
    private ResidentialAllocationRepository allocationRepository;
    private ProvisioningService provisioningService;
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
        service = new NodeUserService(nodeService, client, operationService, allocationRepository, provisioningService);

        nodeId = UUID.randomUUID();
        node = new ManagedNode("Node A", "http://node.example:8088", "encrypted-token");
        node.setId(nodeId);
        when(nodeService.getNode(nodeId)).thenReturn(node);
        // Run the actual remote callback so each test exercises the same
        // idempotent delete path used by the production service.
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get())
                .when(operationService)
                .execute(any(), anyString(), anyString(), any(), eq(OperationResponse.class), any());
    }

    @Test
    void missingRemoteUserBy404ReleasesActiveLocalAllocation() {
        ResidentialAllocation allocation = activeAllocation("alice");
        when(allocationRepository.findAllByNodeIdAndControlUserIdAndStateIn(
                eq(nodeId), eq("alice"), any())).thenReturn(List.of(allocation));
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
        when(allocationRepository.findAllByNodeIdAndControlUserIdAndStateIn(
                eq(nodeId), eq("alice"), any())).thenReturn(List.of(allocation));
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
        when(allocationRepository.findAllByNodeIdAndControlUserIdAndStateIn(
                eq(nodeId), eq("alice"), any())).thenReturn(List.of(allocation));
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
        when(allocationRepository.findAllByNodeIdAndControlUserIdAndStateIn(
                eq(nodeId), eq("alice"), any())).thenReturn(List.of(allocation));
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
    void transientRemoteFailureDoesNotReleaseLocalAllocation() {
        ResidentialAllocation allocation = activeAllocation("alice");
        when(client.deleteUser(node, "alice", "delete-502"))
                .thenThrow(new RemoteNodeException(502, "upstream timeout"));

        assertThatThrownBy(() -> service.deleteUser(nodeId, "alice", "delete-502"))
                .isInstanceOf(RemoteNodeException.class)
                .hasMessageContaining("upstream timeout");

        assertThat(allocation.getState()).isEqualTo("ACTIVE");
        assertThat(allocation.getNode()).isSameAs(node);
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
    }

    private ResidentialAllocation activeAllocation(String userId) {
        ResidentialAllocation allocation = new ResidentialAllocation(
                "request-" + userId,
                "request-hash",
                userId,
                "remote-key",
                "socks");
        allocation.assignNode(node);
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
}
