package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.client.RemoteNodeException;
import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ManagedNodeRepository;
import com.example.nodecontrol.domain.ResidentialAllocation;
import com.example.nodecontrol.domain.ResidentialAllocationRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.AllocationView;
import com.example.nodecontrol.dto.ControlPlaneModels.AllocationPageResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.ProvisionRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionBatchResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionResult;
import com.example.nodecontrol.dto.RemoteModels.CreateUserRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.ProxyConfig;
import com.example.nodecontrol.dto.RemoteModels.SocksConnection;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.UserPage;
import com.example.nodecontrol.security.SecretCipher;
import com.example.nodecontrol.service.IpCountryResolver.CountryInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Service
public class ProvisioningService {

    private static final Collection<String> CAPACITY_STATES = List.of("PROVISIONING", "RETRYABLE", "ACTIVE");
    private static final List<String> RESIDENTIAL_PROTOCOLS = List.of("vless", "vmess", "socks");
    private static final List<String> DIRECT_PROTOCOL_LINKS = List.of(
            "vless", "socksAcceleration", "vmess");
    // The normal Node Manager contract returns the three routed acceleration
    // protocols.  Raw SOCKS5 and BitBrowser are an explicit proxy-details
    // capability and must not be required for provisioning to succeed.
    private static final List<String> RESIDENTIAL_PROTOCOL_LINKS = List.of(
            "vless", "socksAcceleration", "vmess");

    private final ResidentialAllocationRepository allocationRepository;
    private final ManagedNodeRepository nodeRepository;
    private final NodeManagerClient client;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final ControlPlaneProperties properties;
    private final HostAddressResolver hostAddressResolver;
    private final IpCountryResolver ipCountryResolver;
    private final TransactionTemplate transactionTemplate;
    private final AuditLogService auditLogService;

    public ProvisioningService(ResidentialAllocationRepository allocationRepository,
                               ManagedNodeRepository nodeRepository,
                               NodeManagerClient client,
                               SecretCipher secretCipher,
                               ObjectMapper objectMapper,
                               ControlPlaneProperties properties,
                               HostAddressResolver hostAddressResolver,
                               IpCountryResolver ipCountryResolver,
                               PlatformTransactionManager transactionManager,
                               AuditLogService auditLogService) {
        this.allocationRepository = allocationRepository;
        this.nodeRepository = nodeRepository;
        this.client = client;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.hostAddressResolver = hostAddressResolver;
        this.ipCountryResolver = ipCountryResolver;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.auditLogService = auditLogService;
    }

    public AllocationView provision(String idempotencyKey, ProvisionRequest request) {
        return provision(idempotencyKey, request, null);
    }

    public AllocationView provision(String idempotencyKey, ProvisionRequest request, UUID actorUserId) {
        String requestKey = normalizeRequestKey(idempotencyKey);
        validateProtocols(request.protocols());
        ProvisionRequest effectiveRequest = withEffectiveUserId(request, requestKey);
        String requestHash = hash(effectiveRequest);
        ResidentialAllocation allocation = createOrLoad(requestKey, requestHash, effectiveRequest);
        AllocationView result = executeProvisioning(allocation, effectiveRequest, null);
        audit("ALLOCATION_PROVISIONED", actorUserId, allocation.getId(), "创建节点分配");
        return result;
    }

    public ProxyProvisionBatchResponse provisionProxyBatch(String idempotencyKey,
                                                           ProxyProvisionRequest request) {
        return provisionProxyBatch(idempotencyKey, request, null);
    }

    public ProxyProvisionBatchResponse provisionProxyBatch(String idempotencyKey,
                                                           ProxyProvisionRequest request,
                                                           UUID actorUserId) {
        String batchKey = normalizeRequestKey(idempotencyKey);
        List<ProxyRowParseResult> parsedRows = parseProxyRows(request.input());
        List<PreparedProxyRow> preparedRows = new ArrayList<>();
        List<ProxyProvisionResult> results = new ArrayList<>();
        for (ProxyRowParseResult parsed : parsedRows) {
            if (parsed.error() != null) {
                results.add(new ProxyProvisionResult(
                        parsed.rowNumber(), parsed.sourceIp(), parsed.sourceDomain(),
                        parsed.sourceAddress(), parsed.sourcePort(),
                        IpCountryResolver.UNKNOWN.name(), IpCountryResolver.UNKNOWN.code(),
                        null, null, parsed.error()));
                continue;
            }
            ParsedProxyRow row = parsed.row();
            try {
                if (row.directSocksFormat() && request.preferredNodeId() == null) {
                    throw new IllegalArgumentException("四列简写必须指定节点管理器");
                }
                String userId = normalizeBatchUserId(row.username(), batchKey, row.rowNumber());
                String rowKey = rowRequestKey(batchKey, row.rowNumber());
                CountryInfo country = resolveCountry(row.sourceIp());
                ProxyConfig proxy = new ProxyConfig(
                        "socks5", row.server(), row.port(), row.username(), row.password(),
                        row.sourceIp(),
                        row.sourceAddress(),
                        country.code(), country.name(), null);
                ProvisionRequest provisionRequest = new ProvisionRequest(
                        userId, RESIDENTIAL_PROTOCOLS, request.preferredNodeId());
                ProxyRequestHash hashInput = new ProxyRequestHash(
                        provisionRequest, row.sourceIp(), row.sourceAddress(), proxy);
                ResidentialAllocation allocation = createOrLoadProxy(
                        rowKey, hash(hashInput), provisionRequest, row, proxy);
                preparedRows.add(new PreparedProxyRow(row, provisionRequest, proxy, allocation));
            } catch (RuntimeException exception) {
                results.add(toProxyResult(
                        row, null, sanitizeError(exception.getMessage(), null)));
            }
        }

        for (PreparedProxyRow prepared : preparedRows) {
            try {
                AllocationView allocation = executeProvisioning(
                        prepared.allocation(), prepared.request(), prepared.proxy());
                results.add(toProxyResult(prepared.row(), withoutProxyCredentials(allocation), null));
            } catch (RuntimeException exception) {
                ResidentialAllocation failed = allocationRepository.findById(prepared.allocation().getId())
                        .orElse(prepared.allocation());
                String error = sanitizeError(exception.getMessage(), prepared.proxy());
                results.add(toProxyResult(prepared.row(), toViewWithoutProxyCredentials(failed), error));
            }
        }
        results.sort(Comparator.comparingInt(ProxyProvisionResult::rowNumber));
        int succeeded = (int) results.stream()
                .filter(this::isSuccessfulResidentialResult)
                .count();
        ProxyProvisionBatchResponse response = new ProxyProvisionBatchResponse(
                results.size(), succeeded, results.size() - succeeded, results);
        audit("PROXY_BATCH_PROVISIONED", actorUserId, batchKey,
                "批量创建住宅 SOCKS 节点：成功 " + succeeded + "，失败 " + (results.size() - succeeded));
        return response;
    }

    public AllocationView retry(UUID allocationId) {
        return retry(allocationId, null);
    }

    public AllocationView retry(UUID allocationId, UUID actorUserId) {
        ResidentialAllocation allocation = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new NoSuchElementException("分配记录不存在"));
        if ("ACTIVE".equals(allocation.getState())) {
            AllocationView view = toView(allocation);
            audit("ALLOCATION_RETRIED", actorUserId, allocationId, "分配已激活，无需重试");
            return view;
        }
        if ("PROVISIONING".equals(allocation.getState())) {
            throw new IllegalStateException("该分配正在开通中");
        }
        if (!("PENDING".equals(allocation.getState())
                || "RETRYABLE".equals(allocation.getState())
                || "FAILED".equals(allocation.getState()))) {
            throw new IllegalStateException("该分配当前不能重新开通");
        }
        ProvisionRequest request = new ProvisionRequest(
                allocation.getControlUserId(),
                splitProtocols(allocation.getProtocols()),
                null);
        AllocationView view = executeProvisioning(allocation, request, proxyFrom(allocation));
        audit("ALLOCATION_RETRIED", actorUserId, allocationId, "重试节点分配");
        return view;
    }

    private AllocationView executeProvisioning(ResidentialAllocation allocation,
                                                ProvisionRequest request,
                                                ProxyConfig proxy) {
        PreparedProvisioning prepared = prepare(allocation.getId(), request, proxy);
        if (prepared.activeView() != null) {
            if (proxy != null) {
                validateResidentialAllocation(prepared.activeView());
            }
            return prepared.activeView();
        }
        CreateUserRequest remoteRequest = new CreateUserRequest(
                prepared.userId(),
                prepared.protocols(),
                null,
                null,
                proxy
        );

        try {
            CreateUserResponse response = client.createUser(
                    prepared.node(), remoteRequest, prepared.remoteIdempotencyKey());
            if (proxy != null) {
                validateResidentialResponse(response);
            }
            return complete(prepared.allocationId(), response);
        } catch (RemoteNodeException exception) {
            if (exception.getStatusCode() == 409) {
                try {
                    UserConnection existing = client.getConnections(prepared.node(), prepared.userId());
                    CreateUserResponse recovered = new CreateUserResponse(
                            existing.success(), existing.userId(), existing.uuid(), existing.protocols(),
                            existing.vless(), existing.vmess(), existing.socks(), existing.proxyBound(),
                            existing.protocolsAll(), existing.protocolInfo());
                    if (proxy != null) {
                        validateResidentialResponse(recovered);
                    }
                    return complete(prepared.allocationId(), recovered);
                } catch (RuntimeException ignored) {
                    // The original conflict is more useful than a failed reconciliation lookup.
                }
            }
            fail(prepared.allocationId(), sanitizeError(exception.getMessage(), proxy),
                    isDefinitiveFailure(exception));
            throw exception;
        } catch (RuntimeException exception) {
            fail(prepared.allocationId(), sanitizeError(exception.getMessage(), proxy), false);
            throw exception;
        }
    }

    public List<AllocationView> listAllocations() {
        return allocationRepository.findAllBy(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(allocation -> toView(allocation, false))
                .toList();
    }

    public AllocationPageResponse listAllocations(int page, int pageSize) {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须从 1 开始");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("每页条数必须在 1-100 之间");
        }
        Page<ResidentialAllocation> result = allocationRepository.findAllBy(
                PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<AllocationView> items = result.getContent().stream()
                .map(allocation -> toView(allocation, false))
                .toList();
        return new AllocationPageResponse(items, page, pageSize, result.getTotalElements(), result.getTotalPages());
    }

    public AllocationView getAllocation(UUID allocationId) {
        return toView(allocationRepository.findById(allocationId)
                .orElseThrow(() -> new NoSuchElementException("分配记录不存在")), true);
    }

    private ResidentialAllocation createOrLoad(String requestKey,
                                               String requestHash,
                                               ProvisionRequest request) {
        ResidentialAllocation result = transactionTemplate.execute(status -> {
            ResidentialAllocation existing = allocationRepository.findByRequestKey(requestKey).orElse(null);
            if (existing != null) {
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw new IllegalStateException("Idempotency-Key 已被不同的开通请求使用");
                }
                return existing;
            }
            ResidentialAllocation allocation = new ResidentialAllocation(
                    requestKey,
                    requestHash,
                    request.userId().trim(),
                    remoteIdempotencyKey(requestKey),
                    String.join(",", request.protocols())
            );
            try {
                return allocationRepository.saveAndFlush(allocation);
            } catch (DataIntegrityViolationException exception) {
                throw new IllegalStateException("开通请求或用户 ID 已存在", exception);
            }
        });
        if (result == null) {
            throw new IllegalStateException("创建节点分配记录失败");
        }
        return result;
    }

    private ResidentialAllocation createOrLoadProxy(String requestKey,
                                                    String requestHash,
                                                    ProvisionRequest request,
                                                    ParsedProxyRow row,
                                                    ProxyConfig proxy) {
        ResidentialAllocation result = transactionTemplate.execute(status -> {
            ResidentialAllocation existing = allocationRepository.findByRequestKey(requestKey).orElse(null);
            if (existing != null) {
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw new IllegalStateException("Idempotency-Key 已被不同的 SOCKS 开通请求使用");
                }
                return existing;
            }
            ResidentialAllocation allocation = new ResidentialAllocation(
                    requestKey,
                    requestHash,
                    request.userId().trim(),
                    remoteIdempotencyKey(requestKey),
                    String.join(",", request.protocols()),
                    "UPSTREAM_SOCKS",
                    row.rowNumber(),
                    row.sourceIp(),
                    row.sourceAddress(),
                    row.port(),
                    proxy.server(),
                    proxy.port(),
                    secretCipher.encrypt(proxy.username()),
                    secretCipher.encrypt(proxy.password()));
            try {
                return allocationRepository.saveAndFlush(allocation);
            } catch (DataIntegrityViolationException exception) {
                throw new IllegalStateException("SOCKS 开通请求或用户 ID 已存在", exception);
            }
        });
        if (result == null) {
            throw new IllegalStateException("创建 SOCKS 节点分配记录失败");
        }
        return result;
    }

    private PreparedProvisioning prepare(UUID allocationId,
                                         ProvisionRequest request,
                                         ProxyConfig proxy) {
        Set<String> proxyServerAddresses = resolveProxyServerAddresses(proxy);
        PreparedProvisioning prepared = transactionTemplate.execute(
                status -> prepareLocked(allocationId, request, proxy, proxyServerAddresses));
        if (prepared == null) {
            throw new IllegalStateException("准备节点开通任务失败");
        }
        return prepared;
    }

    private PreparedProvisioning prepareLocked(UUID allocationId,
                                                ProvisionRequest request,
                                                ProxyConfig proxy,
                                                Set<String> proxyServerAddresses) {
        ResidentialAllocation allocation = allocationRepository.findLockedById(allocationId)
                .orElseThrow(() -> new NoSuchElementException("分配记录不存在"));
        if ("ACTIVE".equals(allocation.getState())) {
            return PreparedProvisioning.active(toView(allocation));
        }
        if ("PROVISIONING".equals(allocation.getState()) && !isStale(allocation)) {
            throw new IllegalStateException("相同开通请求正在执行中");
        }

        ManagedNode node = allocation.getNode();
        if (node == null) {
            node = selectNode(request.preferredNodeId(), proxy, proxyServerAddresses);
        } else if (!isAllocatable(node)) {
            throw new IllegalStateException("该分配首次选中的节点当前不可用，请恢复节点后重试");
        } else if (wouldProxyLoopThroughNode(node, proxy, proxyServerAddresses)) {
            if (request.preferredNodeId() != null) {
                throw proxyLoopException(true);
            }
            node = selectNode(null, proxy, proxyServerAddresses);
        }
        ensureUserIdAvailableOnNode(
                node,
                allocation.getControlUserId(),
                allocation.getId(),
                "PENDING".equals(allocation.getState()));
        allocation.assignNode(node);
        allocationRepository.saveAndFlush(allocation);
        return new PreparedProvisioning(
                allocation.getId(),
                allocation.getControlUserId(),
                splitProtocols(allocation.getProtocols()),
                allocation.getRemoteIdempotencyKey(),
                node,
                null
        );
    }

    private ManagedNode selectNode(UUID preferredNodeId,
                                   ProxyConfig proxy,
                                   Set<String> proxyServerAddresses) {
        List<ManagedNode> nodes = nodeRepository.findAllocatableNodesForUpdate();
        // The proxy server is the upstream SOCKS endpoint, not a Node Manager
        // endpoint.  Node selection is controlled only by preferredNodeId (when
        // supplied) or by the normal online/capacity selection below.
        if (preferredNodeId != null) {
            nodes = nodes.stream().filter(node -> node.getId().equals(preferredNodeId)).toList();
            if (nodes.isEmpty()) {
                throw new IllegalStateException("指定节点当前不可用于自动开通");
            }
            if (wouldProxyLoopThroughNode(nodes.getFirst(), proxy, proxyServerAddresses)) {
                throw proxyLoopException(true);
            }
        }
        return nodes.stream()
                .filter(this::hasCapacity)
                .filter(node -> !wouldProxyLoopThroughNode(node, proxy, proxyServerAddresses))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有在线且有剩余容量的节点管理器"));
    }

    private Set<String> resolveProxyServerAddresses(ProxyConfig proxy) {
        if (proxy == null || proxy.server() == null || proxy.server().isBlank()) {
            return Set.of();
        }
        String server = normalizeServerHost(proxy.server());
        if (isIpLiteral(server)) {
            return Set.of(canonicalIp(server));
        }
        Set<String> resolved = hostAddressResolver.resolve(server);
        if (resolved == null || resolved.isEmpty()) {
            return Set.of();
        }
        return resolved.stream()
                .map(this::normalizeServerHost)
                .filter(value -> value != null && isIpLiteral(value))
                .map(this::canonicalIp)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private boolean wouldProxyLoopThroughNode(ManagedNode node,
                                              ProxyConfig proxy,
                                              Set<String> proxyServerAddresses) {
        if (proxy == null || proxyServerAddresses == null || proxyServerAddresses.isEmpty()) {
            return false;
        }
        String nodeServer = serverIdentity(node);
        if (nodeServer == null || !proxyServerAddresses.contains(nodeServer)) {
            return false;
        }
        Integer nodeSocksPort = node.getSocksInboundPort();
        // New Node Manager heartbeats report the actual SOCKS inbound port.
        // Only the same server *and* same port is a loop; another SOCKS
        // service on the VPS is a valid upstream.  Keep the IP-only fallback
        // for older agents until their next successful heartbeat.
        return nodeSocksPort == null || nodeSocksPort == proxy.port();
    }

    private IllegalStateException proxyLoopException(boolean preferredNode) {
        String prefix = preferredNode ? "指定节点的服务器与上游 SOCKS 地址相同" : "没有可用的其他节点承载该上游 SOCKS";
        return new IllegalStateException(prefix + "，会形成代理回环，请选择其他节点");
    }

    private boolean hasCapacity(ManagedNode node) {
        long managed = allocationRepository.countByNodeIdAndStateIn(node.getId(), CAPACITY_STATES);
        return Math.max(node.getUserCount(), managed) < node.getMaxUsers();
    }

    /**
     * A node user id is local to the selected Node Manager.  Only active or
     * in-flight allocations on that same node are conflicts; terminal history
     * and allocations belonging to other nodes must not block a new user.
     */
    /**
     * Validate a manually-created node user using the same server-scoped
     * uniqueness rule as automatic provisioning.
     */
    public void ensureUserIdAvailableOnNode(ManagedNode node, String userId) {
        ensureUserIdAvailableOnNode(node, userId, null, true);
    }

    private void ensureUserIdAvailableOnNode(ManagedNode node,
                                             String userId,
                                             UUID ignoredAllocationId,
                                             boolean checkRemoteNodeUsers) {
        String targetServer = serverIdentity(node);
        if (targetServer == null) {
            throw new IllegalStateException("无法识别节点服务器 IP，请先刷新节点心跳后重试");
        }
        if (checkRemoteNodeUsers) {
            ensureRemoteUserIdAvailableOnServer(node, targetServer, userId);
        }
        List<ResidentialAllocation> conflicts = allocationRepository
                .findAllByControlUserIdAndStateIn(userId, CAPACITY_STATES)
                .stream()
                .filter(candidate -> ignoredAllocationId == null
                        || !candidate.getId().equals(ignoredAllocationId))
                .filter(candidate -> sameServer(targetServer, candidate.getNode()))
                .toList();
        for (ResidentialAllocation conflict : conflicts) {
            ManagedNode conflictNode = conflict.getNode();
            if (conflictNode == null) {
                releaseStaleAllocation(conflict);
                continue;
            }
            try {
                UserConnection remoteUser = client.getConnections(conflictNode, conflict.getControlUserId());
                if (remoteUser == null) {
                    throw new IllegalStateException("无法确认节点用户是否仍存在，请检查节点状态后重试");
                }
                if (remoteUser.success()) {
                    throw new IllegalStateException(
                            "节点用户 ID 已存在于节点 " + conflictNode.getName() + " 的分配记录中");
                }
                releaseStaleAllocation(conflict);
            } catch (RemoteNodeException exception) {
                if (isRemoteUserMissing(exception)) {
                    releaseStaleAllocation(conflict);
                    continue;
                }
                throw new IllegalStateException("无法确认节点用户是否仍存在，请检查节点状态后重试");
            }
        }
    }

    /**
     * Local allocation history is not authoritative when a user was created
     * directly on Node Manager or after the history was cleaned up.  Query
     * the selected node's user list before creating a new user so the rule is
     * enforced by the actual server IP + username pair.
     */
    private void ensureRemoteUserIdAvailableOnServer(ManagedNode targetNode,
                                                     String targetServer,
                                                     String userId) {
        // A VPS may have been registered more than once (different ports or
        // API domains).  The uniqueness rule is still server-IP scoped, so
        // inspect every registered Node Manager for that server, not only
        // the node selected for this request.  This also catches users that
        // were created directly on an older/duplicate registration and have
        // no residential_allocations history in Control Plane.
        List<ManagedNode> serverNodes = new ArrayList<>();
        serverNodes.add(targetNode);
        nodeRepository.findAll().stream()
                .filter(candidate -> candidate != null && candidate != targetNode)
                .filter(candidate -> targetServer.equalsIgnoreCase(serverIdentity(candidate)))
                .forEach(serverNodes::add);

        for (ManagedNode node : serverNodes) {
            final UserPage page;
            try {
                page = client.getUsers(node, 1, 100, userId);
            } catch (RemoteNodeException exception) {
                throw new IllegalStateException("无法读取节点用户列表，请检查节点状态后重试");
            }
            // Mockito-based/unit test doubles and very old agents may return
            // no body.  A real Node Manager response is always a UserPage;
            // leave the allocation check as the fallback for a null body.
            if (page == null || page.items() == null) {
                continue;
            }
            boolean exists = page.items().stream()
                    .filter(item -> item != null && item.userId() != null)
                    .anyMatch(item -> userId.equals(item.userId()));
            if (exists) {
                throw new IllegalStateException("节点用户 ID 已存在于当前服务器的节点用户中");
            }
        }
    }

    private boolean sameServer(String targetServer, ManagedNode otherNode) {
        if (targetServer == null || otherNode == null) {
            return false;
        }
        String otherServer = serverIdentity(otherNode);
        return otherServer != null && targetServer.equalsIgnoreCase(otherServer);
    }

    private String serverIdentity(ManagedNode node) {
        if (node == null) {
            return null;
        }
        // The heartbeat host is the authoritative server identity when it is
        // an IP address. Some Node Manager versions report a hostname
        // instead, while the registered baseUrl still contains the public
        // IP; use that IP so two registrations of the same VPS cannot bypass
        // the server+username uniqueness rule.
        String heartbeatHost = normalizeServerHost(node.getHost());
        String baseUrlHost = null;
        try {
            URI uri = URI.create(node.getBaseUrl());
            baseUrlHost = normalizeServerHost(uri.getHost());
        } catch (RuntimeException ignored) {
            // A malformed base URL is rejected during node registration, but
            // old rows may still exist.  Keep the heartbeat value as a
            // best-effort fallback for those rows.
        }
        if (isIpLiteral(heartbeatHost)) {
            return canonicalIp(heartbeatHost);
        }
        if (isIpLiteral(baseUrlHost)) {
            return canonicalIp(baseUrlHost);
        }
        // A hostname alone is not a stable server identity for this rule:
        // two registrations may use different aliases for the same VPS, and
        // DNS can change over time.  Require an actual IP from the heartbeat
        // or the registered Node Manager URL instead of silently falling
        // back to a hostname and allowing a duplicate user.
        return null;
    }

    private String normalizeServerHost(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private boolean isIpLiteral(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) {
            String[] parts = value.split("\\.");
            for (String part : parts) {
                try {
                    int number = Integer.parseInt(part);
                    if (number < 0 || number > 255) {
                        return false;
                    }
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            return true;
        }
        return value.contains(":") && value.matches("^[0-9a-f:]+$");
    }

    private String canonicalIp(String value) {
        if (!isIpLiteral(value)) {
            return value;
        }
        try {
            return InetAddress.getByName(value).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException ignored) {
            return value;
        }
    }

    private void releaseStaleAllocation(ResidentialAllocation allocation) {
        allocation.fail("远端节点用户已删除，已释放本地分配记录", true);
        allocationRepository.save(allocation);
    }

    private boolean isRemoteUserMissing(RemoteNodeException exception) {
        if (exception.getStatusCode() == 404) {
            return true;
        }
        if (exception.getStatusCode() != 409 || exception.getMessage() == null) {
            return false;
        }
        String message = exception.getMessage().trim().toLowerCase(Locale.ROOT);
        // Node Manager currently returns "user not found", while older or
        // localized versions may return an equivalent Chinese message.  Only
        // treat an explicit missing-user response as stale; other 409
        // conflicts must continue to block recreation.
        return message.contains("user not found")
                || message.contains("user does not exist")
                || message.contains("用户不存在")
                || message.contains("用户未找到")
                || message.contains("用户不存在于");
    }

    private boolean isAllocatable(ManagedNode node) {
        return node.isEnabled()
                && !node.isMaintenance()
                && ("online".equals(node.getStatus()) || "degraded".equals(node.getStatus()));
    }

    private AllocationView complete(UUID allocationId, CreateUserResponse response) {
        AllocationView result = transactionTemplate.execute(status -> {
            ResidentialAllocation allocation = allocationRepository.findLockedById(allocationId)
                    .orElseThrow(() -> new NoSuchElementException("分配记录不存在"));
            Map<String, Object> protocolInfoToSave = response.protocolInfo();
            if ("UPSTREAM_SOCKS".equals(allocation.getProvisioningMode())) {
                protocolInfoToSave = enrichProtocolInfo(protocolInfoToSave, allocation);
            }
            allocation.complete(
                    response,
                    secretCipher.encrypt(response.vless()),
                    secretCipher.encrypt(response.vmess()),
                    encryptProtocolsAll(response.protocolsAll()),
                    encryptProtocolInfo(protocolInfoToSave),
                    response.socks() == null ? null : secretCipher.encrypt(response.socks().username()),
                    response.socks() == null ? null : secretCipher.encrypt(response.socks().password()));
            allocationRepository.save(allocation);
            return toView(allocation);
        });
        if (result == null) {
            throw new IllegalStateException("完成节点开通记录失败");
        }
        return result;
    }

    private String encryptProtocolsAll(Map<String, String> protocolsAll) {
        if (protocolsAll == null || protocolsAll.isEmpty()) {
            return null;
        }
        try {
            return secretCipher.encrypt(objectMapper.writeValueAsString(protocolsAll));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("保存节点协议连接失败", exception);
        }
    }

    private String encryptProtocolInfo(Map<String, Object> protocolInfo) {
        if (protocolInfo == null || protocolInfo.isEmpty()) {
            return null;
        }
        try {
            return secretCipher.encrypt(objectMapper.writeValueAsString(protocolInfo));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("保存节点协议参数失败", exception);
        }
    }

    private void fail(UUID allocationId, String error, boolean definitive) {
        transactionTemplate.executeWithoutResult(status -> allocationRepository.findLockedById(allocationId)
                .ifPresent(allocation -> allocation.fail(error, definitive)));
    }

    private boolean isDefinitiveFailure(RemoteNodeException exception) {
        int status = exception.getStatusCode();
        return status >= 400 && status < 500 && status != 408 && status != 409 && status != 429;
    }

    private boolean isStale(ResidentialAllocation allocation) {
        long staleAfter = Math.max(1000, properties.getProvisioning().getOperationStaleAfterMs());
        return allocation.getUpdatedAt() == null
                || allocation.getUpdatedAt().isBefore(Instant.now().minus(Duration.ofMillis(staleAfter)));
    }

    private AllocationView toView(ResidentialAllocation allocation) {
        return toView(allocation, true);
    }

    private AllocationView toView(ResidentialAllocation allocation, boolean includeConnection) {
        ManagedNode node = allocation.getNode();
        UserConnection connection = null;
        String proxyUsername = null;
        String proxyPassword = null;
        if (includeConnection && "UPSTREAM_SOCKS".equals(allocation.getProvisioningMode())) {
            proxyUsername = secretCipher.decrypt(allocation.getProxyUsernameCipher());
            proxyPassword = secretCipher.decrypt(allocation.getProxyPasswordCipher());
        }
        if (includeConnection && "ACTIVE".equals(allocation.getState())) {
            SocksConnection socks = allocation.getSocksHost() == null ? null : new SocksConnection(
                    allocation.getSocksHost(),
                    allocation.getSocksPort(),
                    secretCipher.decrypt(allocation.getSocksUsernameCipher()),
                    secretCipher.decrypt(allocation.getSocksPasswordCipher()));
            Map<String, String> protocolsAll = decryptProtocolsAll(allocation.getProtocolsAllCipher());
            Map<String, Object> protocolInfo = decryptProtocolInfo(allocation.getProtocolInfoCipher());
            if ("UPSTREAM_SOCKS".equals(allocation.getProvisioningMode())) {
                protocolInfo = enrichProtocolInfo(protocolInfo, allocation);
            }
            connection = new UserConnection(
                    true,
                    allocation.getRemoteUserId(),
                    allocation.getConnectionUuid(),
                    splitProtocols(allocation.getProtocols()),
                    secretCipher.decrypt(allocation.getVlessCipher()),
                    secretCipher.decrypt(allocation.getVmessCipher()),
                    socks,
                    allocation.isProxyBound(),
                    allocation.getCompletedAt(),
                    protocolsAll,
                    protocolInfo);
        }
        Map<String, String> protocolsAll = connection == null ? Map.of() : connection.protocolsAll();
        Map<String, Object> protocolInfo = connection == null ? Map.of() : connection.protocolInfo();
        return new AllocationView(
                allocation.getId(),
                allocation.getRequestKey(),
                allocation.getControlUserId(),
                splitProtocols(allocation.getProtocols()),
                allocation.getState(),
                node == null ? null : node.getId(),
                node == null ? null : node.getName(),
                node == null ? null : node.getHost(),
                connection,
                protocolsAll,
                protocolInfo,
                allocation.getLastError(),
                allocation.getCreatedAt(),
                allocation.getUpdatedAt(),
                allocation.getCompletedAt(),
                allocation.getProvisioningMode(),
                allocation.isProxyBound(),
                allocation.getProxyServer(),
                allocation.getProxyPort(),
                proxyUsername,
                proxyPassword,
                allocation.getProxySourceIp(),
                allocation.getProxyServer() != null
                        ? allocation.getProxyServer()
                        : allocation.getProxySourceDomain(),
                allocation.getProxyPort() != null
                        ? allocation.getProxyPort()
                        : allocation.getProxySourcePort());
    }

    private Map<String, String> decryptProtocolsAll(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> value = objectMapper.readValue(
                    secretCipher.decrypt(cipherText),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            return value == null ? Map.of() : value;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("节点协议连接数据格式无效", exception);
        }
    }

    private Map<String, Object> decryptProtocolInfo(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(
                    secretCipher.decrypt(cipherText),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            return value == null ? Map.of() : value;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("节点协议参数数据格式无效", exception);
        }
    }

    /**
     * 为住宅（上游 SOCKS）分配补全原始链接所需参数，并修正加速地址。
     *
     * Node Manager 返回的 protocolInfo 只有一套 ip/username/password（源自节点本地
     * 配置），无法同时满足两类语义：原始链接（SOCKS5 原始 / BitBrowser）应使用住宅
     * 出口 IP + 上游代理凭据，加速链接应指向节点记录的真实地址。此处把这两类数据
     * 显式拆分注入，供前端本地拼接。
     */
    private Map<String, Object> enrichProtocolInfo(Map<String, Object> protocolInfo,
                                                   ResidentialAllocation allocation) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        if (protocolInfo != null) {
            enriched.putAll(protocolInfo);
        }
        // 原始链接：住宅出口 IP + 上游端口/凭据（sourceIp/rawUsername/rawPassword/rawPort）
        if (allocation.getProxySourceIp() != null) {
            enriched.put("sourceIp", allocation.getProxySourceIp());
        }
        if (allocation.getProxyServer() != null) {
            // The raw/original SOCKS connection must use the upstream access
            // address, while acceleration links use accelerationDomain.
            enriched.put("sourceAddress", allocation.getProxyServer());
            enriched.put("rawServer", allocation.getProxyServer());
        }
        if (allocation.getProxyPort() != null) {
            enriched.put("rawPort", allocation.getProxyPort());
            enriched.put("sourcePort", allocation.getProxyPort());
        }
        String rawUsername = secretCipher.decrypt(allocation.getProxyUsernameCipher());
        String rawPassword = secretCipher.decrypt(allocation.getProxyPasswordCipher());
        if (rawUsername != null) {
            enriched.put("rawUsername", rawUsername);
        }
        if (rawPassword != null) {
            enriched.put("rawPassword", rawPassword);
        }
        // 加速链接：VLESS/VMess/SOCKS 加速链接连接的是节点服务器上 sing-box 的
        // 入站端口，因此 accelerationDomain 必须是节点服务器的 host（如 203.0.113.20），
        // 而不是上游 SOCKS 的地址（proxyServer）。Node Manager 已经返回了正确的
        // accelerationDomain（其自身 host），此处仅在缺失时用节点 host 补全。
        ManagedNode node = allocation.getNode();
        String nodeHost = node == null ? null : normalizeServerHost(node.getHost());
        String accelerationHost = normalizeServerHost(asString(enriched.get("accelerationDomain")));
        if (nodeHost != null && (accelerationHost == null || isInvalidAccelerationHost(accelerationHost, allocation))) {
            enriched.put("accelerationDomain", node.getHost().trim());
        } else if (accelerationHost != null) {
            enriched.put("accelerationDomain", accelerationHost);
        }
        return enriched;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private boolean isInvalidAccelerationHost(String candidate, ResidentialAllocation allocation) {
        return sameHost(candidate, allocation.getProxySourceIp())
                || sameHost(candidate, allocation.getProxyServer())
                || sameHost(candidate, allocation.getProxySourceDomain());
    }

    private boolean sameHost(String left, String right) {
        String a = normalizeServerHost(left);
        String b = normalizeServerHost(right);
        if (a == null || b == null) {
            return false;
        }
        if (isIpLiteral(a) && isIpLiteral(b)) {
            return canonicalIp(a).equals(canonicalIp(b));
        }
        return a.equalsIgnoreCase(b);
    }

    private List<ProxyRowParseResult> parseProxyRows(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("请输入 SOCKS 节点信息");
        }
        List<ProxyRowParseResult> rows = new ArrayList<>();
        String normalizedInput = input.replace('\u00A0', ' ')
                .replace('\u3000', ' ')
                .replace("\uFEFF", "");
        String[] lines = normalizedInput.split("\\R", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].trim();
            if (line.isBlank()) {
                continue;
            }
            int rowNumber = lineIndex + 1;
            // 先尝试 socks:// / socks5:// URL 格式
            if (line.startsWith("socks://") || line.startsWith("socks5://")) {
                rows.add(parseSocksUrl(rowNumber, line));
                continue;
            }
            String[] columns = line.split("\\s+");
            boolean indexed = columns.length == 6;
            boolean shortFormat = columns.length == 4;
            String sourceIp = candidateColumn(columns, indexed ? 1 : 0);
            String sourceDomain = normalizeSourceAddressCandidate(
                    candidateColumn(columns, indexed ? 2 : shortFormat ? 0 : 1));
            String sourceAddress = sourceDomain == null ? sourceIp : sourceDomain;
            Integer sourcePort = findPort(columns);
            try {
                ParsedProxyRow row = switch (columns.length) {
                    case 4 -> parsedRow(rowNumber, columns[0], columns[0], columns[1], columns[2], columns[3], true);
                    case 5 -> parsedRow(rowNumber, columns[0], columns[1], columns[2], columns[3], columns[4], false);
                    case 6 -> parsedRow(rowNumber, columns[1], columns[2], columns[3], columns[4], columns[5], false);
                    default -> throw new IllegalArgumentException(
                            "第 " + rowNumber + " 行格式错误，应为 4 列、5 列或带序号的 6 列");
                };
                rows.add(new ProxyRowParseResult(
                        rowNumber, row.sourceIp(), row.sourceAddress(), row.server(), row.port(), row, null));
            } catch (IllegalArgumentException exception) {
                rows.add(new ProxyRowParseResult(
                        rowNumber, sourceIp, sourceDomain, sourceAddress,
                        sourcePort, null, exception.getMessage()));
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("请输入至少一行 SOCKS 节点信息");
        }
        if (rows.size() > 50) {
            throw new IllegalArgumentException("单次最多生成 50 个节点用户");
        }
        return rows;
    }

    private ProxyRowParseResult parseSocksUrl(int rowNumber, String line) {
        URI uri;
        try {
            uri = URI.create(line);
        } catch (IllegalArgumentException ex) {
            return new ProxyRowParseResult(rowNumber, null, null, null, null, null,
                    "第 " + rowNumber + " 行 socks:// 链接格式不合法：" + ex.getMessage());
        }
        String scheme = uri.getScheme();
        if (!"socks".equalsIgnoreCase(scheme) && !"socks5".equalsIgnoreCase(scheme)) {
            return new ProxyRowParseResult(rowNumber, null, null, null, null, null,
                    "第 " + rowNumber + " 行协议不支持，仅支持 socks:// 或 socks5://");
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isBlank()) {
            return new ProxyRowParseResult(rowNumber, null, null, null, null, null,
                    "第 " + rowNumber + " 行 socks:// 链接缺少 host");
        }
        if (port <= 0 || port > 65535) {
            return new ProxyRowParseResult(rowNumber, null, null, null, null, null,
                    "第 " + rowNumber + " 行 socks:// 链接端口不合法");
        }
        String userInfo = uri.getUserInfo();
        String username;
        String password;
        if (userInfo == null || userInfo.isBlank()) {
            return new ProxyRowParseResult(rowNumber, null, null, null, null, null,
                    "第 " + rowNumber + " 行 socks:// 链接缺少账号密码（user:pass）");
        }
        int colon = userInfo.indexOf(':');
        try {
            if (colon < 0) {
                username = java.net.URLDecoder.decode(userInfo, java.nio.charset.StandardCharsets.UTF_8);
                password = "";
            } else {
                username = java.net.URLDecoder.decode(userInfo.substring(0, colon), java.nio.charset.StandardCharsets.UTF_8);
                password = java.net.URLDecoder.decode(userInfo.substring(colon + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException ex) {
            return new ProxyRowParseResult(rowNumber, null, null, null, null, null,
                    "第 " + rowNumber + " 行 socks:// 链接账号密码 URL 解码失败：" + ex.getMessage());
        }
        // 从 fragment（# 后内容）中提取出口 IP，例如：#%5BUS%5D%20150.241.188.77 → [US] 150.241.188.77
        String sourceIp = null;
        String fragment = uri.getFragment();
        if (fragment != null && !fragment.isBlank()) {
            String decodedFragment = java.net.URLDecoder.decode(fragment, java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{1,3}(?:\\.\\d{1,3}){3})").matcher(decodedFragment);
            if (m.find()) {
                sourceIp = m.group(1);
            }
        }
        try {
            ParsedProxyRow row;
            if (sourceIp != null && !sourceIp.equals(host)) {
                // 有独立出口 IP → 5 列格式：sourceIp(出口) + server(host入口)
                row = parsedRow(rowNumber, sourceIp, host, String.valueOf(port), username, password, false);
            } else {
                // 4 列简写：host 既是接入地址，也是默认 sourceIp（之后走节点）
                row = parsedRow(rowNumber, host, host, String.valueOf(port), username, password, true);
            }
            return new ProxyRowParseResult(rowNumber, row.sourceIp(), row.sourceAddress(), row.server(), row.port(), row, null);
        } catch (IllegalArgumentException ex) {
            return new ProxyRowParseResult(rowNumber, sourceIp, host, host, port, null, ex.getMessage());
        }
    }

    private ParsedProxyRow parsedRow(int rowNumber,
                                     String sourceIp,
                                     String sourceDomain,
                                     String portValue,
                                     String username,
                                     String password,
                                     boolean directSocksFormat) {
        String ip = requireColumn(sourceIp, rowNumber, "IP");
        String sourceAddress = sourceDomain == null || sourceDomain.isBlank() || "-".equals(sourceDomain)
                ? null : sourceDomain.trim();
        validateIp(ip, rowNumber);
        if (sourceAddress != null) {
            validateSourceAddress(sourceAddress, rowNumber);
        }
        int port;
        try {
            port = Integer.parseInt(portValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行端口不是有效数字");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行端口必须在 1-65535 之间");
        }
        String normalizedUsername = requireColumn(username, rowNumber, "账号");
        String normalizedPassword = requireColumn(password, rowNumber, "密码");
        validateCredential(normalizedUsername, rowNumber, "账号");
        validateCredential(normalizedPassword, rowNumber, "密码");
        return new ParsedProxyRow(
                rowNumber, ip, sourceAddress, sourceAddress == null ? ip : sourceAddress, port,
                normalizedUsername, normalizedPassword, directSocksFormat);
    }

    private String candidateColumn(String[] columns, int index) {
        return index >= 0 && index < columns.length ? columns[index] : null;
    }

    private String normalizeSourceAddressCandidate(String value) {
        return value == null || value.isBlank() || "-".equals(value) ? null : value;
    }

    private Integer findPort(String[] columns) {
        int index = columns.length == 6 ? 3 : columns.length == 4 ? 1 : 2;
        if (columns.length <= index) {
            return null;
        }
        try {
            return Integer.parseInt(columns[index]);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void validateIp(String value, int rowNumber) {
        if (!value.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行 IP 地址格式不正确");
        }
        String[] segments = value.split("\\.");
        for (String segment : segments) {
            int part = Integer.parseInt(segment);
            if (part < 0 || part > 255) {
                throw new IllegalArgumentException("第 " + rowNumber + " 行 IP 地址格式不正确");
            }
        }
        try {
            InetAddress.getByName(value);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行 IP 地址格式不正确");
        }
    }

    private void validateDomain(String value, int rowNumber) {
        String ascii;
        try {
            ascii = IDN.toASCII(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行域名格式不正确");
        }
        if (ascii.length() > 253
                || !ascii.matches("(?i)^(?=.{1,253}\\.?$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.?$")) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行域名格式不正确");
        }
    }

    private void validateSourceAddress(String value, int rowNumber) {
        if (value.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) {
            validateIp(value, rowNumber);
            return;
        }
        try {
            validateDomain(value, rowNumber);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行 SOCKS 接入地址格式不正确");
        }
    }

    private void validateCredential(String value, int rowNumber, String name) {
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行" + name + "不能包含空白字符");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行" + name + "不能包含控制字符");
        }
    }

    private String requireColumn(String value, int rowNumber, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行" + name + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行" + name + "不能超过 255 个字符");
        }
        return normalized;
    }

    private ProvisionRequest withEffectiveUserId(ProvisionRequest request, String requestKey) {
        String userId = request.userId();
        if (userId == null || userId.isBlank()) {
            userId = defaultUserId(requestKey);
        } else {
            userId = normalizeNodeUserId(userId, "用户 ID");
        }
        return new ProvisionRequest(userId, request.protocols(), request.preferredNodeId());
    }

    private String normalizeBatchUserId(String username, String batchKey, int rowNumber) {
        if (username == null || username.isBlank()) {
            return String.format(Locale.ROOT, "node-%s-%02d", sha256Hex(batchKey).substring(0, 12), rowNumber);
        }
        return normalizeNodeUserId(username, "第 " + rowNumber + " 行 SOCKS 用户名");
    }

    private String normalizeNodeUserId(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (normalized.length() > 64 || !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(label + "只能包含字母、数字、点、下划线和短横线，且不超过 64 个字符");
        }
        return normalized;
    }

    private String defaultUserId(String requestKey) {
        return "node-" + sha256Hex(requestKey).substring(0, 16);
    }

    private String rowRequestKey(String batchKey, int rowNumber) {
        return "proxy-" + sha256Hex(batchKey) + "-" + rowNumber;
    }

    private String remoteIdempotencyKey(String requestKey) {
        return "provision-" + sha256Hex(requestKey);
    }

    private ProxyConfig proxyFrom(ResidentialAllocation allocation) {
        if (!"UPSTREAM_SOCKS".equals(allocation.getProvisioningMode())) {
            return null;
        }
        if (allocation.getProxyServer() == null || allocation.getProxyPort() == null) {
            throw new IllegalStateException("SOCKS 分配记录缺少上游代理信息");
        }
        return new ProxyConfig(
                "socks5",
                allocation.getProxyServer(),
                allocation.getProxyPort(),
                secretCipher.decrypt(allocation.getProxyUsernameCipher()),
                secretCipher.decrypt(allocation.getProxyPasswordCipher()),
                allocation.getProxySourceIp(),
                allocation.getProxyServer(),
                null, null, null);
    }

    private ProxyProvisionResult toProxyResult(ParsedProxyRow row,
                                               AllocationView allocation,
                                               String error) {
        CountryInfo country = resolveCountry(row.sourceIp());
        // The generated protocol links live in allocation.connection.  Do not
        // return an upstream SOCKS URI here: it would expose the submitted
        // upstream username/password and is not the routed node-user entry.
        String socksLink = null;
        return new ProxyProvisionResult(
                row.rowNumber(), row.sourceIp(), row.sourceAddress(),
                row.server(), row.port(), country.name(), country.code(), socksLink, allocation, error);
    }

    private CountryInfo resolveCountry(String sourceIp) {
        try {
            CountryInfo country = ipCountryResolver.resolve(sourceIp);
            return country == null ? IpCountryResolver.UNKNOWN : country;
        } catch (RuntimeException ignored) {
            return IpCountryResolver.UNKNOWN;
        }
    }

    /**
     * Batch responses are not the explicit proxy-details endpoint. Keep the
     * generated connection links available for the current page, but never
     * include the upstream SOCKS username/password in this response.
     */
    private AllocationView toViewWithoutProxyCredentials(ResidentialAllocation allocation) {
        AllocationView view = toView(allocation, true);
        return withoutProxyCredentials(view);
    }

    private AllocationView withoutProxyCredentials(AllocationView view) {
        if (view == null) {
            return view;
        }
        UserConnection connection = sanitizeConnection(view.connection());
        return new AllocationView(
                view.id(),
                view.requestKey(),
                view.userId(),
                view.protocols(),
                view.state(),
                view.nodeId(),
                view.nodeName(),
                view.nodeHost(),
                connection,
                sanitizeProtocolsAll(view.protocolsAll()),
                sanitizeProtocolInfo(view.protocolInfo()),
                view.lastError(),
                view.createdAt(),
                view.updatedAt(),
                view.completedAt(),
                view.provisioningMode(),
                view.proxyBound(),
                view.proxyServer(),
                view.proxyPort(),
                null,
                null,
                view.sourceIp(),
                view.sourceAddress(),
                view.sourcePort());
    }

    private UserConnection sanitizeConnection(UserConnection connection) {
        if (connection == null) {
            return null;
        }
        return new UserConnection(
                connection.success(),
                connection.userId(),
                connection.uuid(),
                connection.protocols(),
                connection.vless(),
                connection.vmess(),
                connection.socks(),
                connection.proxyBound(),
                connection.createdAt(),
                sanitizeProtocolsAll(connection.protocolsAll()),
                sanitizeProtocolInfo(connection.protocolInfo()));
    }

    private Map<String, String> sanitizeProtocolsAll(Map<String, String> protocolsAll) {
        if (protocolsAll == null || protocolsAll.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (String key : DIRECT_PROTOCOL_LINKS) {
            String value = protocolsAll.get(key);
            if (hasText(value)) {
                sanitized.put(key, value);
            }
        }
        return sanitized;
    }

    private Map<String, Object> sanitizeProtocolInfo(Map<String, Object> protocolInfo) {
        if (protocolInfo == null || protocolInfo.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(protocolInfo);
        // These fields identify the upstream residential SOCKS endpoint or
        // carry its credentials.  They are only allowed in the explicit
        // proxy-details response, never in batch/normal connection payloads.
        List.of("rawProtocol", "rawServer", "rawPort", "rawUsername", "rawPassword",
                "sourceAddress", "sourcePort").forEach(sanitized::remove);
        return sanitized;
    }

    private boolean isSuccessfulResidentialResult(ProxyProvisionResult result) {
        return result.error() == null && isResidentialAllocationComplete(result.allocation());
    }

    private void validateResidentialResponse(CreateUserResponse response) {
        if (response == null
                || !response.success()
                || !hasResidentialProtocols(response.protocols())
                || !hasText(response.vless())
                || !hasText(response.vmess())
                || !hasUsableSocksConnection(response.socks())
                || !response.proxyBound()) {
            throw new IllegalStateException("节点管理器未返回已绑定原生住宅出口的 VLESS、VMess、SOCKS5 三协议连接");
        }
        if (properties.getProvisioning().isRequireCompleteProtocolsAll()
                && !hasCompleteProtocolsAll(response.protocolsAll(), response.proxyBound())) {
            throw new IllegalStateException("节点管理器未返回完整的五协议连接，请先升级 Node Manager");
        }
    }

    private void validateResidentialAllocation(AllocationView allocation) {
        if (!isResidentialAllocationComplete(allocation)) {
            throw new IllegalStateException("原生住宅节点记录缺少已绑定出口的 VLESS、VMess、SOCKS5 三协议连接");
        }
    }

    private boolean isResidentialAllocationComplete(AllocationView allocation) {
        if (allocation == null || !"ACTIVE".equals(allocation.state()) || !allocation.proxyBound()) {
            return false;
        }
        UserConnection connection = allocation.connection();
        return connection != null
                && connection.success()
                && connection.proxyBound()
                && hasResidentialProtocols(connection.protocols())
                && hasText(connection.vless())
                && hasText(connection.vmess())
                && hasUsableSocksConnection(connection.socks())
                && (!properties.getProvisioning().isRequireCompleteProtocolsAll()
                || hasCompleteProtocolsAll(connection.protocolsAll(), connection.proxyBound()));
    }

    private boolean hasCompleteProtocolsAll(Map<String, String> protocolsAll, boolean proxyBound) {
        if (protocolsAll == null || protocolsAll.isEmpty()) {
            return false;
        }
        List<String> required = proxyBound ? RESIDENTIAL_PROTOCOL_LINKS : DIRECT_PROTOCOL_LINKS;
        return required.stream().allMatch(key -> hasText(protocolsAll.get(key)));
    }

    private boolean hasResidentialProtocols(List<String> protocols) {
        if (protocols == null) {
            return false;
        }
        return protocols.stream()
                .filter(value -> value != null)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(RESIDENTIAL_PROTOCOLS);
    }

    private boolean hasUsableSocksConnection(SocksConnection socks) {
        return socks != null
                && hasText(socks.host())
                && socks.port() >= 1
                && socks.port() <= 65535
                && hasText(socks.username())
                && hasText(socks.password());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String sanitizeError(String message, ProxyConfig proxy) {
        String sanitized = message == null || message.isBlank() ? "节点开通失败" : message;
        if (proxy == null) {
            return truncateError(sanitized);
        }
        for (String secret : List.of(proxy.username(), proxy.password())) {
            if (secret != null && !secret.isBlank()) {
                sanitized = sanitized.replace(secret, "***");
            }
        }
        return truncateError(sanitized);
    }

    private String truncateError(String message) {
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private void audit(String eventType, UUID actorUserId, Object targetId, String summary) {
        if (auditLogService != null) {
            auditLogService.record(eventType, actorUserId, "ALLOCATION",
                    targetId == null ? null : String.valueOf(targetId), summary);
        }
    }

    private String normalizeRequestKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("自动开通必须提供 Idempotency-Key");
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key 不能超过 128 个字符");
        }
        return normalized;
    }

    private void validateProtocols(List<String> protocols) {
        if (protocols == null || protocols.isEmpty()) {
            throw new IllegalArgumentException("至少选择一种协议");
        }
        if (new LinkedHashSet<>(protocols).size() != protocols.size()) {
            throw new IllegalArgumentException("协议列表不能包含重复值");
        }
    }

    private String hash(Object request) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(request);
            return sha256Hex(bytes);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("计算节点开通请求摘要失败", exception);
        }
    }

    private String sha256Hex(String value) {
        try {
            return sha256Hex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("计算节点开通幂等键摘要失败", exception);
        }
    }

    private String sha256Hex(byte[] value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private List<String> splitProtocols(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(","));
    }

    private record PreparedProvisioning(
            UUID allocationId,
            String userId,
            List<String> protocols,
            String remoteIdempotencyKey,
            ManagedNode node,
            AllocationView activeView
    ) {
        static PreparedProvisioning active(AllocationView view) {
            return new PreparedProvisioning(null, null, List.of(), null, null, view);
        }
    }

    private record ParsedProxyRow(
            int rowNumber,
            String sourceIp,
            String sourceAddress,
            String server,
            int port,
            String username,
            String password,
            boolean directSocksFormat
    ) {
    }

    private record ProxyRowParseResult(
            int rowNumber,
            String sourceIp,
            String sourceDomain,
            String sourceAddress,
            Integer sourcePort,
            ParsedProxyRow row,
            String error
    ) {
    }

    private record PreparedProxyRow(
            ParsedProxyRow row,
            ProvisionRequest request,
            ProxyConfig proxy,
            ResidentialAllocation allocation
    ) {
    }

    private record ProxyRequestHash(
            ProvisionRequest provision,
            String sourceIp,
            String sourceAddress,
            ProxyConfig proxy
    ) {
    }
}
