package com.example.nodecontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ManagedNodeRepository extends JpaRepository<ManagedNode, UUID> {

    Optional<ManagedNode> findByBaseUrl(String baseUrl);
}

