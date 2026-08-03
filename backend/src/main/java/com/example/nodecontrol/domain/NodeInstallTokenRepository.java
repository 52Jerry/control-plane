package com.example.nodecontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface NodeInstallTokenRepository extends JpaRepository<NodeInstallToken, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update NodeInstallToken token
               set token.claimId = :claimId, token.claimedAt = :claimedAt
             where token.tokenHash = :tokenHash
               and token.expiresAt > :now
               and token.usedAt is null
               and (token.claimedAt is null or token.claimedAt < :claimStaleBefore)
            """)
    int claimAvailable(@Param("tokenHash") String tokenHash,
                       @Param("claimId") String claimId,
                       @Param("claimedAt") Instant claimedAt,
                       @Param("now") Instant now,
                       @Param("claimStaleBefore") Instant claimStaleBefore);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update NodeInstallToken token
               set token.claimId = null, token.claimedAt = null
             where token.tokenHash = :tokenHash
               and token.claimId = :claimId
               and token.usedAt is null
            """)
    int releaseClaim(@Param("tokenHash") String tokenHash,
                     @Param("claimId") String claimId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update NodeInstallToken token
               set token.usedAt = :usedAt, token.usedNodeId = :usedNodeId
             where token.tokenHash = :tokenHash
               and token.claimId = :claimId
               and token.usedAt is null
            """)
    int markUsed(@Param("tokenHash") String tokenHash,
                 @Param("claimId") String claimId,
                 @Param("usedAt") Instant usedAt,
                 @Param("usedNodeId") UUID usedNodeId);

    @Modifying
    @Transactional
    long deleteByExpiresAtBefore(Instant cutoff);
}
