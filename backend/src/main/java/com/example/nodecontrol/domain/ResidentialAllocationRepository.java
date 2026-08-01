package com.example.nodecontrol.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResidentialAllocationRepository extends JpaRepository<ResidentialAllocation, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<ResidentialAllocation> {

    @Override
    @EntityGraph(attributePaths = "node")
    Optional<ResidentialAllocation> findById(UUID id);

    @EntityGraph(attributePaths = "node")
    Optional<ResidentialAllocation> findByRequestKey(String requestKey);

    @EntityGraph(attributePaths = "node")
    Optional<ResidentialAllocation> findByControlUserId(String controlUserId);

    @EntityGraph(attributePaths = "node")
    List<ResidentialAllocation> findAllBy(Sort sort);

    long countByNodeIdAndStateIn(UUID nodeId, Collection<String> states);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select allocation from ResidentialAllocation allocation where allocation.id = :id")
    Optional<ResidentialAllocation> findLockedById(UUID id);
}
