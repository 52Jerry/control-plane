package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ManagedNodeRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.RegisterNodeRequest;
import com.example.nodecontrol.dto.RemoteModels.AgentHeartbeat;
import com.example.nodecontrol.dto.RemoteModels.AgentInfo;
import com.example.nodecontrol.dto.RemoteModels.TrafficTotals;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedNodeServiceTest {

    @Test
    void registersAndExposesHeartbeatWithoutLeakingToken() {
        ManagedNodeRepository repository = mock(ManagedNodeRepository.class);
        NodeManagerClient client = mock(NodeManagerClient.class);
        ManagedNodeService service = new ManagedNodeService(repository, client, new ControlPlaneProperties());

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
        verify(repository).save(any(ManagedNode.class));
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
