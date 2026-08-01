package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.ControlPlaneModels.AllocationView;
import com.example.nodecontrol.dto.ControlPlaneModels.ProvisionRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionBatchResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionRequest;
import com.example.nodecontrol.service.ProvisioningService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/control/allocations")
public class ProvisioningController {

    private final ProvisioningService provisioningService;

    public ProvisioningController(ProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @PostMapping
    public ResponseEntity<AllocationView> provision(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ProvisionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(provisioningService.provision(idempotencyKey, request));
    }

    @PostMapping("/proxy-provisions")
    public ResponseEntity<ProxyProvisionBatchResponse> provisionProxyBatch(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ProxyProvisionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(provisioningService.provisionProxyBatch(idempotencyKey, request));
    }

    @GetMapping
    public ResponseEntity<List<AllocationView>> list() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(provisioningService.listAllocations());
    }

    @GetMapping("/{allocationId}")
    public ResponseEntity<AllocationView> get(@PathVariable UUID allocationId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(provisioningService.getAllocation(allocationId));
    }

    @PostMapping("/{allocationId}/retry")
    public ResponseEntity<AllocationView> retry(@PathVariable UUID allocationId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(provisioningService.retry(allocationId));
    }
}
