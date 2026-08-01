package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.ControlPlaneModels.DashboardView;
import com.example.nodecontrol.dto.ControlPlaneModels.NodeView;
import com.example.nodecontrol.dto.ControlPlaneModels.RegisterNodeRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.UpdateNodeRequest;
import com.example.nodecontrol.dto.RemoteModels.ReloadResponse;
import com.example.nodecontrol.service.ManagedNodeService;
import com.example.nodecontrol.service.NodeUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/control")
public class NodeController {

    private final ManagedNodeService nodeService;
    private final NodeUserService userService;

    public NodeController(ManagedNodeService nodeService, NodeUserService userService) {
        this.nodeService = nodeService;
        this.userService = userService;
    }

    @GetMapping("/dashboard")
    public DashboardView dashboard() {
        return nodeService.getDashboard();
    }

    @GetMapping("/nodes")
    public List<NodeView> nodes() {
        return nodeService.listNodes();
    }

    @PostMapping("/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeView register(@Valid @RequestBody RegisterNodeRequest request) {
        return nodeService.register(request);
    }

    @PostMapping("/nodes/{nodeId}/refresh")
    public NodeView refresh(@PathVariable UUID nodeId) {
        return nodeService.refresh(nodeId);
    }

    @PatchMapping("/nodes/{nodeId}")
    public NodeView update(@PathVariable UUID nodeId,
                           @Valid @RequestBody UpdateNodeRequest request) {
        return nodeService.updateNode(nodeId, request);
    }

    @PostMapping("/nodes/{nodeId}/reload")
    public ReloadResponse reload(@PathVariable UUID nodeId) {
        return userService.reload(nodeId);
    }

    @DeleteMapping("/nodes/{nodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID nodeId) {
        nodeService.deleteNode(nodeId);
    }
}

