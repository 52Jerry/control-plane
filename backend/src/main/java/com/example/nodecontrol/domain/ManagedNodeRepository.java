package com.example.nodecontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManagedNodeRepository extends JpaRepository<ManagedNode, UUID> {

    Optional<ManagedNode> findByBaseUrl(String baseUrl);

    Optional<ManagedNode> findByRemoteNodeId(String remoteNodeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select node from ManagedNode node where node.enabled = true and node.maintenance = false and node.status in ('online', 'degraded') order by node.userCount asc, node.cpu asc, node.connections asc")
    List<ManagedNode> findAllocatableNodesForUpdate();
}

