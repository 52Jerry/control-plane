package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.ControlPlaneModels.AuditLogView;
import com.example.nodecontrol.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/control/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public ResponseEntity<Page<AuditLogView>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(auditLogService.list(page, pageSize));
    }
}
