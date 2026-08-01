package com.example.nodecontrol.service;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.domain.ControlUser;
import com.example.nodecontrol.domain.ControlUserRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.ControlUserView;
import com.example.nodecontrol.dto.ControlPlaneModels.CreateControlUserRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.UpdateControlUserRequest;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class ControlAccountService {

    private final ControlUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final ControlPlaneProperties properties;

    public ControlAccountService(ControlUserRepository repository,
                                 PasswordEncoder passwordEncoder,
                                 ControlPlaneProperties properties) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapInitialAccount() {
        if (repository.count() != 0) {
            return;
        }
        String username = properties.getSecurity().getLoginUsername();
        String password = properties.getSecurity().getLoginPassword();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return;
        }
        validateUsername(username.trim());
        validatePassword(password);
        repository.save(new ControlUser(username.trim(), passwordEncoder.encode(password)));
    }

    @Transactional
    public Optional<ControlUser> authenticate(String username, String password) {
        if (!StringUtils.hasText(username) || password == null) {
            return Optional.empty();
        }
        Optional<ControlUser> match = repository.findByUsernameIgnoreCase(username.trim())
                .filter(ControlUser::isEnabled)
                .filter(user -> passwordEncoder.matches(password, user.getPasswordHash()));
        match.ifPresent(user -> {
            user.recordLogin();
            repository.save(user);
        });
        return match;
    }

    public List<ControlUserView> list(UUID currentUserId) {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(ControlUser::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(user -> toView(user, currentUserId))
                .toList();
    }

    @Transactional
    public ControlUserView create(CreateControlUserRequest request, UUID currentUserId) {
        String username = request.username().trim();
        validateUsername(username);
        validatePassword(request.password());
        if (repository.findByUsernameIgnoreCase(username).isPresent()) {
            throw new IllegalStateException("管理账号已经存在");
        }
        ControlUser user = repository.save(new ControlUser(username, passwordEncoder.encode(request.password())));
        return toView(user, currentUserId);
    }

    @Transactional
    public ControlUserView update(UUID userId, UpdateControlUserRequest request, UUID currentUserId) {
        ControlUser user = get(userId);
        if (Boolean.FALSE.equals(request.enabled())) {
            if (userId.equals(currentUserId)) {
                throw new IllegalStateException("不能停用当前登录账号");
            }
            if (user.isEnabled() && repository.countByEnabledTrue() <= 1) {
                throw new IllegalStateException("至少需要保留一个可登录账号");
            }
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        if (request.password() != null) {
            validatePassword(request.password());
            user.changePasswordHash(passwordEncoder.encode(request.password()));
        }
        return toView(repository.save(user), currentUserId);
    }

    @Transactional
    public void delete(UUID userId, UUID currentUserId) {
        ControlUser user = get(userId);
        if (userId.equals(currentUserId)) {
            throw new IllegalStateException("不能删除当前登录账号");
        }
        if (user.isEnabled() && repository.countByEnabledTrue() <= 1) {
            throw new IllegalStateException("至少需要保留一个可登录账号");
        }
        repository.delete(user);
    }

    private ControlUser get(UUID userId) {
        return repository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("管理账号不存在"));
    }

    private void validateUsername(String username) {
        if (username == null || !username.matches("^[A-Za-z0-9._-]{3,64}$")) {
            throw new IllegalArgumentException("管理账号只能包含字母、数字、点、下划线和短横线，长度为 3-64 位");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 10 || password.length() > 128) {
            throw new IllegalArgumentException("管理密码长度必须为 10-128 位");
        }
    }

    private ControlUserView toView(ControlUser user, UUID currentUserId) {
        return new ControlUserView(
                user.getId(),
                user.getUsername(),
                user.isEnabled(),
                user.getId().equals(currentUserId),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getLastLoginAt());
    }
}
