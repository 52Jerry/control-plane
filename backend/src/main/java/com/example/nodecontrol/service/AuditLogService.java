package com.example.nodecontrol.service;

import com.example.nodecontrol.domain.AuditLog;
import com.example.nodecontrol.domain.AuditLogRepository;
import com.example.nodecontrol.domain.ControlUserRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.AuditLogView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditLogService {

    private final AuditLogRepository repository;
    private final ControlUserRepository userRepository;

    public AuditLogService(AuditLogRepository repository, ControlUserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void record(String eventType, UUID actorUserId, String targetType, String targetId, String summary) {
        String safeSummary = sanitize(summary);
        String actorUsername = actorUserId == null ? null
                : userRepository.findById(actorUserId).map(user -> user.getUsername()).orElse(null);
        repository.save(new AuditLog(eventType, actorUserId, actorUsername,
                trim(targetType, 64), trim(targetId, 128), safeSummary));
    }

    @Transactional(readOnly = true)
    public Page<AuditLogView> list(int page, int pageSize) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        return repository.findAllByOrderByCreatedAtDesc(
                        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(log -> new AuditLogView(log.getId(), log.getEventType(), log.getActorUserId(),
                        log.getActorUsername(), log.getTargetType(), log.getTargetId(), log.getSummary(),
                        log.getCreatedAt()));
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) return "操作完成";
        String result = value.replaceAll("(?i)(password|token|secret|api[-_]?key|authorization)\\s*[:=]\\s*[^,; ]+", "$1=***");
        return trim(result, 500);
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
