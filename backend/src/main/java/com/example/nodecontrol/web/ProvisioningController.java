package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.ControlPlaneModels.AllocationView;
import com.example.nodecontrol.dto.ControlPlaneModels.AllocationPageResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.ProvisionRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionBatchResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionRequest;
import com.example.nodecontrol.service.ProvisioningService;
import com.example.nodecontrol.security.ControlSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/control/allocations")
public class ProvisioningController {

    private final ProvisioningService provisioningService;
    private final ControlSessionService sessionService;

    public ProvisioningController(ProvisioningService provisioningService, ControlSessionService sessionService) {
        this.provisioningService = provisioningService;
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<AllocationView> provision(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ProvisionRequest request,
            HttpServletRequest servletRequest
    ) {
        UUID actor = actor(servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(actor == null
                        ? provisioningService.provision(idempotencyKey, request)
                        : provisioningService.provision(idempotencyKey, request, actor));
    }

    @PostMapping("/proxy-provisions")
    public ResponseEntity<ProxyProvisionBatchResponse> provisionProxyBatch(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ProxyProvisionRequest request,
            HttpServletRequest servletRequest
    ) {
        UUID actor = actor(servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(actor == null
                        ? provisioningService.provisionProxyBatch(idempotencyKey, request)
                        : provisioningService.provisionProxyBatch(idempotencyKey, request, actor));
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize
    ) {
        if ((page == null) != (pageSize == null)) {
            throw new IllegalArgumentException("page 和 pageSize 必须同时提供");
        }
        Object body = page == null
                ? provisioningService.listAllocations()
                : provisioningService.listAllocations(page, pageSize);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @GetMapping("/{allocationId}")
    public ResponseEntity<AllocationView> get(@PathVariable UUID allocationId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(provisioningService.getAllocation(allocationId));
    }

    @PostMapping("/{allocationId}/retry")
    public ResponseEntity<AllocationView> retry(@PathVariable UUID allocationId, HttpServletRequest servletRequest) {
        UUID actor = actor(servletRequest);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(actor == null
                        ? provisioningService.retry(allocationId)
                        : provisioningService.retry(allocationId, actor));
    }

    private UUID actor(HttpServletRequest request) {
        return sessionService.authenticatedSession(request).map(ControlSessionService.AuthenticatedSession::userId).orElse(null);
    }
}
