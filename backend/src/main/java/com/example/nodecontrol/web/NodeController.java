package com.example.nodecontrol.web;

import com.example.nodecontrol.dto.ControlPlaneModels.DashboardView;
import com.example.nodecontrol.dto.ControlPlaneModels.NodeView;
import com.example.nodecontrol.dto.ControlPlaneModels.RegisterNodeRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.UpdateNodeRequest;
import com.example.nodecontrol.dto.RemoteModels.ReloadResponse;
import com.example.nodecontrol.service.ManagedNodeService;
import com.example.nodecontrol.service.NodeUserService;
import com.example.nodecontrol.security.ControlSessionService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ControlSessionService sessionService;

    public NodeController(ManagedNodeService nodeService, NodeUserService userService, ControlSessionService sessionService) {
        this.nodeService = nodeService;
        this.userService = userService;
        this.sessionService = sessionService;
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
    public NodeView register(@Valid @RequestBody RegisterNodeRequest request, HttpServletRequest servletRequest) {
        UUID actor = actor(servletRequest);
        return actor == null ? nodeService.register(request) : nodeService.register(request, actor);
    }

    @PostMapping("/nodes/{nodeId}/refresh")
    public NodeView refresh(@PathVariable UUID nodeId, HttpServletRequest servletRequest) {
        UUID actor = actor(servletRequest);
        return actor == null ? nodeService.refresh(nodeId) : nodeService.refresh(nodeId, actor);
    }

    @PatchMapping("/nodes/{nodeId}")
    public NodeView update(@PathVariable UUID nodeId,
                           @Valid @RequestBody UpdateNodeRequest request,
                           HttpServletRequest servletRequest) {
        UUID actor = actor(servletRequest);
        return actor == null
                ? nodeService.updateNode(nodeId, request)
                : nodeService.updateNode(nodeId, request, actor);
    }

    @PostMapping("/nodes/{nodeId}/reload")
    public ReloadResponse reload(@PathVariable UUID nodeId) {
        return userService.reload(nodeId);
    }

    @DeleteMapping("/nodes/{nodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID nodeId, HttpServletRequest servletRequest) {
        UUID actor = actor(servletRequest);
        if (actor == null) {
            nodeService.deleteNode(nodeId);
        } else {
            nodeService.deleteNode(nodeId, actor);
        }
    }

    private UUID actor(HttpServletRequest request) {
        return sessionService.authenticatedSession(request).map(ControlSessionService.AuthenticatedSession::userId).orElse(null);
    }
}

