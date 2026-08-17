package com.example.nodecontrol.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;

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
    List<ResidentialAllocation> findAllByNodeIdAndControlUserIdAndStateInAndIdNot(
            UUID nodeId, String controlUserId, Collection<String> states, UUID allocationId);

    @EntityGraph(attributePaths = "node")
    List<ResidentialAllocation> findAllByNodeIdAndControlUserIdAndStateIn(
            UUID nodeId, String controlUserId, Collection<String> states);

    @EntityGraph(attributePaths = "node")
    List<ResidentialAllocation> findAllByControlUserIdAndStateIn(
            String controlUserId, Collection<String> states);

    @EntityGraph(attributePaths = "node")
    List<ResidentialAllocation> findAllByNodeId(UUID nodeId);

    @EntityGraph(attributePaths = "node")
    List<ResidentialAllocation> findAllByNodeIdAndControlUserIdInAndStateIn(
            UUID nodeId, Collection<String> controlUserIds, Collection<String> states);

    @EntityGraph(attributePaths = "node")
    List<ResidentialAllocation> findAllBy(Sort sort);

    @EntityGraph(attributePaths = "node")
    Page<ResidentialAllocation> findAllBy(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "node")
    List<ResidentialAllocation> findAll(Specification<ResidentialAllocation> specification, Sort sort);

    @Override
    @EntityGraph(attributePaths = "node")
    Page<ResidentialAllocation> findAll(Specification<ResidentialAllocation> specification, Pageable pageable);

    long countByNodeIdAndStateIn(UUID nodeId, Collection<String> states);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select allocation from ResidentialAllocation allocation where allocation.id = :id")
    Optional<ResidentialAllocation> findLockedById(UUID id);
}
