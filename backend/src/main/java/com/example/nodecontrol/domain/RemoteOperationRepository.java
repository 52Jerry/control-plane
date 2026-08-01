package com.example.nodecontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RemoteOperationRepository extends JpaRepository<RemoteOperation, UUID> {

    Optional<RemoteOperation> findByOperationKey(String operationKey);
}
