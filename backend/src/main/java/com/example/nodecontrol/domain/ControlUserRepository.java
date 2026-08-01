package com.example.nodecontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ControlUserRepository extends JpaRepository<ControlUser, UUID> {

    Optional<ControlUser> findByUsernameIgnoreCase(String username);

    boolean existsByEnabledTrue();

    long countByEnabledTrue();
}
