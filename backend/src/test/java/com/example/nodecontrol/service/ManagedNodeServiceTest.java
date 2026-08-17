package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ManagedNodeRepository;
import com.example.nodecontrol.domain.ResidentialAllocationRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.AgentRegistrationRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.RegisterNodeRequest;
import com.example.nodecontrol.dto.RemoteModels.AgentHeartbeat;
import com.example.nodecontrol.dto.RemoteModels.AgentInfo;
import com.example.nodecontrol.dto.RemoteModels.TrafficTotals;
import com.example.nodecontrol.security.SecretCipher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManagedNodeServiceTest {

    @Test
    void skipsScheduledHeartbeatWhenDisabled() {
        ManagedNodeRepository repository = mock(ManagedNodeRepository.class);
        ResidentialAllocationRepository allocationRepository = mock(ResidentialAllocationRepository.class);
        NodeManagerClient client = mock(NodeManagerClient.class);
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.getHeartbeat().setScheduledEnabled(false);
        properties.getSecurity().setEncryptionKey("unit-test-encryption-key");
        ManagedNodeService service = new ManagedNodeService(
                repository, allocationRepository, client, properties, new SecretCipher(properties));

        service.refreshAll();

        verifyNoInteractions(repository, client);
    }

    @Test
    void registersAndExposesHeartbeatWithoutLeakingToken() {
        ManagedNodeRepository repository = mock(ManagedNodeRepository.class);
        ResidentialAllocationRepository allocationRepository = mock(ResidentialAllocationRepository.class);
        NodeManagerClient client = mock(NodeManagerClient.class);
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.getSecurity().setEncryptionKey("unit-test-encryption-key");
        SecretCipher secretCipher = new SecretCipher(properties);
        ManagedNodeService service = new ManagedNodeService(
                repository, allocationRepository, client, properties, secretCipher);

        when(repository.findByBaseUrl("http://node.example:8088")).thenReturn(Optional.empty());
        when(client.getAgentInfo("http://node.example:8088", "secret-token"))
                .thenReturn(new AgentInfo("node-manager", "v1", "1.4.1", "node-a", List.of(), List.of(), "Idempotency-Key", "/api/agent/heartbeat"));
        when(client.getHeartbeat(any(ManagedNode.class))).thenReturn(heartbeat());
        when(repository.save(any(ManagedNode.class))).thenAnswer(invocation -> {
            ManagedNode node = invocation.getArgument(0);
            node.setId(UUID.fromString("39e6cb19-f86c-4644-88a0-fd879db50843"));
            return node;
        });

        var view = service.register(new RegisterNodeRequest("Node A", "http://node.example:8088/", "secret-token"));

        assertThat(view.remoteNodeId()).isEqualTo("node-a");
        assertThat(view.status()).isEqualTo("online");
        assertThat(view.totalTraffic()).isEqualTo(30);
        assertThat(view.toString()).doesNotContain("secret-token");
        assertThat(secretCipher.decrypt(savedNode(repository).getStoredApiToken())).isEqualTo("secret-token");
        verify(repository).save(any(ManagedNode.class));
    }

    @Test
    void installerRegistrationUpdatesExistingNodeByStableRemoteId() {
        ManagedNodeRepository repository = mock(ManagedNodeRepository.class);
        ResidentialAllocationRepository allocationRepository = mock(ResidentialAllocationRepository.class);
        NodeManagerClient client = mock(NodeManagerClient.class);
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.getSecurity().setEncryptionKey("unit-test-encryption-key");
        SecretCipher secretCipher = new SecretCipher(properties);
        ManagedNodeService service = new ManagedNodeService(
                repository, allocationRepository, client, properties, secretCipher);

        ManagedNode existing = new ManagedNode("Old Name", "http://old.example:8088", secretCipher.encrypt("old-token"));
        existing.setId(UUID.fromString("39e6cb19-f86c-4644-88a0-fd879db50843"));
        existing.updateRegistration(
                "Old Name",
                "http://old.example:8088",
                secretCipher.encrypt("old-token"),
                agentInfo("node-a"),
                500);

        when(client.getAgentInfo("https://node.example", "new-token")).thenReturn(agentInfo("node-a"));
        when(client.getHeartbeat(any(ManagedNode.class))).thenReturn(heartbeat());
        when(repository.findByRemoteNodeId("node-a")).thenReturn(Optional.of(existing));
        when(repository.findByBaseUrl("https://node.example")).thenReturn(Optional.empty());
        when(repository.save(existing)).thenReturn(existing);

        var response = service.registerAgent(new AgentRegistrationRequest(
                "node-a", "Node A", "https://node.example/", "new-token",
                "203.0.113.10", "1.4.1", 900));

        assertThat(response.created()).isFalse();
        assertThat(response.id()).isEqualTo(existing.getId());
        assertThat(existing.getName()).isEqualTo("Node A");
        assertThat(existing.getBaseUrl()).isEqualTo("https://node.example");
        assertThat(existing.getMaxUsers()).isEqualTo(900);
        assertThat(secretCipher.decrypt(existing.getStoredApiToken())).isEqualTo("new-token");
    }

    @Test
    void refusesToDeleteNodeWithActiveAllocations() {
        ManagedNodeRepository repository = mock(ManagedNodeRepository.class);
        ResidentialAllocationRepository allocationRepository = mock(ResidentialAllocationRepository.class);
        NodeManagerClient client = mock(NodeManagerClient.class);
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.getSecurity().setEncryptionKey("unit-test-encryption-key");
        ManagedNodeService service = new ManagedNodeService(
                repository, allocationRepository, client, properties, new SecretCipher(properties));
        UUID nodeId = UUID.randomUUID();
        ManagedNode node = new ManagedNode("Node A", "http://node.example", "token");
        node.setId(nodeId);
        when(repository.findById(nodeId)).thenReturn(Optional.of(node));
        when(allocationRepository.countByNodeIdAndStateIn(eq(nodeId), any())).thenReturn(1L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deleteNode(nodeId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能移除");
    }

    @Test
    void decryptsStoredNodeTokenOnlyThroughDedicatedLookup() {
        ManagedNodeRepository repository = mock(ManagedNodeRepository.class);
        ResidentialAllocationRepository allocationRepository = mock(ResidentialAllocationRepository.class);
        NodeManagerClient client = mock(NodeManagerClient.class);
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.getSecurity().setEncryptionKey("unit-test-encryption-key");
        SecretCipher secretCipher = new SecretCipher(properties);
        ManagedNodeService service = new ManagedNodeService(
                repository, allocationRepository, client, properties, secretCipher);
        UUID nodeId = UUID.randomUUID();
        ManagedNode node = new ManagedNode("Node A", "http://node.example", secretCipher.encrypt("node-secret-token"));
        node.setId(nodeId);
        when(repository.findById(nodeId)).thenReturn(Optional.of(node));

        var response = service.getNodeToken(nodeId);

        assertThat(response.nodeId()).isEqualTo(nodeId);
        assertThat(response.token()).isEqualTo("node-secret-token");
    }

    private AgentInfo agentInfo(String nodeId) {
        return new AgentInfo(
                "node-manager", "v1", "1.4.1", nodeId, List.of(), List.of(),
                "Idempotency-Key", "/api/agent/heartbeat");
    }

    private ManagedNode savedNode(ManagedNodeRepository repository) {
        org.mockito.ArgumentCaptor<ManagedNode> captor = org.mockito.ArgumentCaptor.forClass(ManagedNode.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private AgentHeartbeat heartbeat() {
        return new AgentHeartbeat(
                "node-a",
                "Node A",
                "203.0.113.10",
                "online",
                "1.4.1",
                "1.13.14",
                "running",
                true,
                2.5,
                20.5,
                3,
                12,
                4,
                new TrafficTotals(10, 20, 30, true, "clash-api-sampled", Instant.now()),
                Instant.now()
        );
    }
}
