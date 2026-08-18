package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.client.RemoteNodeException;
import com.example.nodecontrol.domain.ControlPlaneSettings;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ResidentialAllocation;
import com.example.nodecontrol.domain.ResidentialAllocationRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.BatchConnectionResult;
import com.example.nodecontrol.dto.ControlPlaneModels.UserPolicyMigrationFailure;
import com.example.nodecontrol.dto.ControlPlaneModels.UserPolicyMigrationResponse;
import com.example.nodecontrol.dto.RemoteModels.BindProxyRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.OperationResponse;
import com.example.nodecontrol.dto.RemoteModels.NodeAccessInfo;
import com.example.nodecontrol.dto.RemoteModels.ProxyDetails;
import com.example.nodecontrol.dto.RemoteModels.ProxyMetadataUpdateRequest;
import com.example.nodecontrol.dto.RemoteModels.ReloadResponse;
import com.example.nodecontrol.dto.RemoteModels.TrafficResponse;
import com.example.nodecontrol.dto.RemoteModels.UpdateUserPolicyRequest;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.UserPage;
import com.example.nodecontrol.dto.RemoteModels.UserPolicyResponse;
import com.example.nodecontrol.dto.RemoteModels.UserSummary;
import com.example.nodecontrol.service.IpCountryResolver.CountryInfo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class NodeUserService {

    private final ManagedNodeService nodeService;
    private final NodeManagerClient client;
    private final RemoteOperationService operationService;
    private final ResidentialAllocationRepository allocationRepository;
    private final ProvisioningService provisioningService;
    private final IpCountryResolver ipCountryResolver;
    private final AuditLogService auditLogService;
    private final UserPolicyDefaultsService policyDefaultsService;

    private static final List<String> ACTIVE_ALLOCATION_STATES =
            List.of("PROVISIONING", "RETRYABLE", "ACTIVE");
    private static final String CREATED_DESC = "createdDesc";
    private static final String CREATED_ASC = "createdAsc";
    private static final long USER_SNAPSHOT_TTL_NANOS = java.time.Duration.ofSeconds(30).toNanos();
    private static final int USER_ACCESS_CONCURRENCY = 8;

    private final Map<UserSnapshotKey, UserSnapshot> userSnapshots = new ConcurrentHashMap<>();

    public NodeUserService(ManagedNodeService nodeService,
                           NodeManagerClient client,
                           RemoteOperationService operationService,
                           ResidentialAllocationRepository allocationRepository,
                           ProvisioningService provisioningService) {
        this(nodeService, client, operationService, allocationRepository, provisioningService,
                null, null, null);
    }

    public NodeUserService(ManagedNodeService nodeService,
                           NodeManagerClient client,
                           RemoteOperationService operationService,
                           ResidentialAllocationRepository allocationRepository,
                           ProvisioningService provisioningService,
                           IpCountryResolver ipCountryResolver,
                           AuditLogService auditLogService) {
        this(nodeService, client, operationService, allocationRepository, provisioningService,
                null, ipCountryResolver, auditLogService);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NodeUserService(ManagedNodeService nodeService,
                           NodeManagerClient client,
                           RemoteOperationService operationService,
                           ResidentialAllocationRepository allocationRepository,
                           ProvisioningService provisioningService,
                           UserPolicyDefaultsService policyDefaultsService,
                           IpCountryResolver ipCountryResolver,
                           AuditLogService auditLogService) {
        this.nodeService = nodeService;
        this.client = client;
        this.operationService = operationService;
        this.allocationRepository = allocationRepository;
        this.provisioningService = provisioningService;
        this.policyDefaultsService = policyDefaultsService;
        this.ipCountryResolver = ipCountryResolver;
        this.auditLogService = auditLogService;
    }

    public UserPage listUsers(UUID nodeId, int page, int pageSize, String keyword) {
        return listUsers(nodeId, page, pageSize, keyword, null, false);
    }

    public UserPage listUsers(UUID nodeId,
                              int page,
                              int pageSize,
                              String keyword,
                              String ip,
                              boolean includeAccessCredentials) {
        return listUsersWithoutSorting(
                nodeId, page, pageSize, keyword, ip, includeAccessCredentials);
    }

    public UserPage listUsers(UUID nodeId,
                              int page,
                              int pageSize,
                              String keyword,
                              String ip,
                              String sort,
                              boolean includeAccessCredentials) {
        return listUsers(
                nodeId, page, pageSize, keyword, ip, sort, includeAccessCredentials, false);
    }

    public UserPage listUsers(UUID nodeId,
                              int page,
                              int pageSize,
                              String keyword,
                              String ip,
                              String sort,
                              boolean includeAccessCredentials,
                              boolean refresh) {
        validatePage(page, pageSize);
        String normalizedSort = normalizeSort(sort);
        ManagedNode node = nodeService.getNode(nodeId);
        boolean filteringByIp = ip != null && !ip.isBlank();
        List<UserSummary> remoteUsers = fetchAllUsers(node, keyword, refresh);
        if (filteringByIp) {
            remoteUsers = enrichUsers(node, remoteUsers, ip, includeAccessCredentials);
        }
        List<UserSummary> sortedUsers = remoteUsers.stream()
                .sorted(createdAtComparator(normalizedSort))
                .toList();
        List<UserSummary> pageItems = page(sortedUsers, page, pageSize);
        if (!filteringByIp) {
            pageItems = enrichUsers(node, pageItems, null, includeAccessCredentials);
        }
        return new UserPage(pageItems, page, pageSize, sortedUsers.size());
    }

    public List<UserSummary> listUsersForExport(UUID nodeId,
                                                String keyword,
                                                String ip,
                                                String sort,
                                                boolean includeAccessCredentials) {
        String normalizedSort = normalizeSort(sort);
        ManagedNode node = nodeService.getNode(nodeId);
        boolean filteringByIp = ip != null && !ip.isBlank();
        List<UserSummary> remoteUsers = fetchAllUsers(node, keyword);
        List<UserSummary> enrichedUsers = enrichUsers(
                node,
                remoteUsers,
                ip,
                includeAccessCredentials,
                filteringByIp);
        return enrichedUsers.stream()
                .sorted(createdAtComparator(normalizedSort))
                .toList();
    }

    private UserPage listUsersWithoutSorting(UUID nodeId,
                                             int page,
                                             int pageSize,
                                             String keyword,
                                             String ip,
                                             boolean includeAccessCredentials) {
        ManagedNode node = nodeService.getNode(nodeId);
        boolean filteringByIp = ip != null && !ip.isBlank();
        if (filteringByIp) {
            List<UserSummary> enriched = enrichUsers(
                    node, fetchAllUsers(node, keyword), ip, includeAccessCredentials);
            List<UserSummary> pageItems = page(enriched, page, pageSize);
            return new UserPage(pageItems, page, pageSize, enriched.size());
        }
        UserPage remotePage = client.getUsers(node, page, pageSize, keyword);
        return new UserPage(
                enrichUsers(node, remotePage.items(), null, includeAccessCredentials),
                remotePage.page(), remotePage.pageSize(), remotePage.total());
    }

    private List<UserSummary> fetchAllUsers(ManagedNode node, String keyword) {
        return fetchAllUsers(node, keyword, false);
    }

    private List<UserSummary> fetchAllUsers(ManagedNode node, String keyword, boolean refresh) {
        String normalizedKeyword = normalizeKeyword(keyword);
        UserSnapshotKey key = new UserSnapshotKey(node.getId(), normalizedKeyword);
        long now = System.nanoTime();
        userSnapshots.entrySet().removeIf(entry ->
                now - entry.getValue().loadedAtNanos() >= USER_SNAPSHOT_TTL_NANOS);
        UserSnapshot snapshot = userSnapshots.compute(key, (ignored, existing) -> {
            if (!refresh && existing != null && now - existing.loadedAtNanos() < USER_SNAPSHOT_TTL_NANOS) {
                return existing;
            }
            return new UserSnapshot(
                    List.copyOf(scanAllUsers(node, normalizedKeyword.isEmpty() ? null : normalizedKeyword)),
                    System.nanoTime());
        });
        return snapshot.users();
    }

    private List<UserSummary> scanAllUsers(ManagedNode node, String keyword) {
        List<UserSummary> remoteUsers = new ArrayList<>();
        int remotePageNumber = 1;
        long remoteTotal;
        do {
            UserPage remotePage = client.getUsers(node, remotePageNumber, 100, keyword);
            if (remotePage == null || remotePage.items() == null || remotePage.items().isEmpty()) {
                break;
            }
            remoteUsers.addAll(remotePage.items());
            remoteTotal = remotePage.total();
            remotePageNumber++;
        } while (remoteUsers.size() < remoteTotal);
        return remoteUsers;
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }

    private void invalidateUserSnapshots(UUID nodeId) {
        userSnapshots.keySet().removeIf(key -> key.nodeId().equals(nodeId));
    }

    private record UserSnapshotKey(UUID nodeId, String keyword) {
    }

    private record UserSnapshot(List<UserSummary> users, long loadedAtNanos) {
    }

    private List<UserSummary> page(List<UserSummary> users, int page, int pageSize) {
        int from = (int) Math.min((long) (page - 1) * pageSize, users.size());
        int to = Math.min(from + pageSize, users.size());
        return users.subList(from, to);
    }

    private Comparator<UserSummary> createdAtComparator(String sort) {
        Comparator<Instant> instantComparator = CREATED_ASC.equals(sort)
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Comparator<UserSummary> comparator = Comparator.comparing(
                UserSummary::createdAt,
                Comparator.nullsLast(instantComparator));
        return comparator.thenComparing(
                UserSummary::userId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null || sort.isBlank() ? CREATED_DESC : sort.trim();
        if (!CREATED_DESC.equals(normalized) && !CREATED_ASC.equals(normalized)) {
            throw new IllegalArgumentException("排序方式仅支持 createdDesc 或 createdAsc");
        }
        return normalized;
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须从 1 开始");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("每页条数必须在 1-100 之间");
        }
    }

    private List<UserSummary> enrichUsers(ManagedNode node,
                                          List<UserSummary> users,
                                          String ip,
                                          boolean includeAccessCredentials) {
        return enrichUsers(node, users, ip, includeAccessCredentials, true);
    }

    private List<UserSummary> enrichUsers(ManagedNode node,
                                          List<UserSummary> users,
                                          String ip,
                                          boolean includeAccessCredentials,
                                          boolean allowRemoteFallback) {
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        List<String> userIds = users.stream().map(UserSummary::userId).toList();
        Collection<String> states = List.of("ACTIVE", "PROVISIONING", "RETRYABLE");
        Map<String, ResidentialAllocation> allocationByUser = new LinkedHashMap<>();
        allocationRepository.findAllByNodeIdAndControlUserIdInAndStateIn(node.getId(), userIds, states)
                .forEach(allocation -> allocationByUser.merge(
                        allocation.getControlUserId(), allocation,
                        (current, candidate) -> newer(current, candidate)));
        List<UserSummary> enriched = enrichUsersConcurrently(
                node, users, allocationByUser, includeAccessCredentials, allowRemoteFallback);
        String normalizedIp = ip == null ? null : ip.trim().toLowerCase(Locale.ROOT);
        return enriched.stream()
                .filter(user -> matchesIp(user.access(), normalizedIp))
                .toList();
    }

    private List<UserSummary> enrichUsersConcurrently(ManagedNode node,
                                                       List<UserSummary> users,
                                                       Map<String, ResidentialAllocation> allocationByUser,
                                                       boolean includeAccessCredentials,
                                                       boolean allowRemoteFallback) {
        if (users.size() == 1) {
            UserSummary user = users.getFirst();
            return List.of(withAccess(user, userAccess(
                    node, user, allocationByUser.get(user.userId()),
                    includeAccessCredentials, allowRemoteFallback)));
        }

        int concurrency = Math.min(USER_ACCESS_CONCURRENCY, users.size());
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
            List<Future<UserSummary>> futures = users.stream()
                    .map(user -> executor.submit(() -> withAccess(user, userAccess(
                            node, user, allocationByUser.get(user.userId()),
                            includeAccessCredentials, allowRemoteFallback))))
                    .toList();
            List<UserSummary> enriched = new ArrayList<>(users.size());
            for (Future<UserSummary> future : futures) {
                try {
                    enriched.add(future.get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("读取节点用户连接信息被中断", exception);
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new IllegalStateException("读取节点用户连接信息失败", cause);
                }
            }
            return enriched;
        }
    }

    private NodeAccessInfo userAccess(ManagedNode node,
                                      UserSummary user,
                                      ResidentialAllocation allocation,
                                      boolean includeAccessCredentials,
                                      boolean allowRemoteFallback) {
        NodeAccessInfo stored = provisioningService.accessInfo(allocation, includeAccessCredentials);
        if (stored != null && stored.ip() != null && stored.port() != null) {
            return stored;
        }
        if (!allowRemoteFallback) {
            return null;
        }
        try {
            NodeAccessInfo remote = provisioningService.accessInfo(
                    client.getConnections(node, user.userId()), includeAccessCredentials);
            if (remote != null) {
                return remote;
            }
        } catch (RuntimeException ignored) {
            // Keep the user list available when one legacy user has incomplete connection data.
        }
        return provisioningService.accessInfo(
                node.getHost(), node.getSocksInboundPort(), user.socksUsername(), includeAccessCredentials);
    }

    private ResidentialAllocation newer(ResidentialAllocation first, ResidentialAllocation second) {
        if (first.getUpdatedAt() == null) return second;
        if (second.getUpdatedAt() == null) return first;
        return second.getUpdatedAt().isAfter(first.getUpdatedAt()) ? second : first;
    }

    private UserSummary withAccess(UserSummary user, NodeAccessInfo access) {
        return new UserSummary(
                user.userId(), user.protocols(), user.socksUsername(), user.proxyBound(), user.proxyServer(),
                user.upload(), user.download(), user.total(), user.trafficLimitBytes(), user.maxSourceIps(),
                user.activeSourceIps(), user.status(), user.createdAt(), access);
    }

    private boolean matchesIp(NodeAccessInfo access, String normalizedIp) {
        return normalizedIp == null || normalizedIp.isBlank()
                || (access != null && access.ip() != null
                && access.ip().toLowerCase(Locale.ROOT).contains(normalizedIp));
    }

    public CreateUserResponse createUser(UUID nodeId, CreateUserRequest request, String idempotencyKey) {
        return createUser(nodeId, request, idempotencyKey, null);
    }

    public CreateUserResponse createUser(UUID nodeId, CreateUserRequest request, String idempotencyKey, UUID actorUserId) {
        ManagedNode node = nodeService.getNode(nodeId);
        CreateUserRequest effectiveRequest = withDefaultPolicy(withProxyCredentials(request));
        provisioningService.ensureUserIdAvailableOnNode(node, effectiveRequest.userId());
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey.trim();
        CreateUserResponse response = operationService.execute(
                node, key, "CREATE_USER", effectiveRequest, CreateUserResponse.class,
                () -> client.createUser(node, effectiveRequest, key));
        if (response != null && response.success()) {
            invalidateUserSnapshots(nodeId);
        }
        audit("USER_CREATED", actorUserId, nodeId, effectiveRequest.userId(), "创建节点用户");
        return response;
    }

    private CreateUserRequest withProxyCredentials(CreateUserRequest request) {
        if (request.proxy() == null
                || request.proxy().username() == null || request.proxy().username().isBlank()
                || request.proxy().password() == null || request.proxy().password().isBlank()) {
            return request;
        }
        return new CreateUserRequest(
                request.userId(), request.protocols(),
                request.proxy().username(), request.proxy().password(), request.proxy(),
                request.trafficLimitBytes(), request.maxSourceIps());
    }

    private CreateUserRequest withDefaultPolicy(CreateUserRequest request) {
        UserPolicyDefaultsService.DefaultUserPolicy defaults = defaultUserPolicy();
        return new CreateUserRequest(
                request.userId(), request.protocols(), request.socksUsername(), request.socksPassword(), request.proxy(),
                request.trafficLimitBytes() == null
                        ? defaults.trafficLimitBytes()
                        : request.trafficLimitBytes(),
                request.maxSourceIps() == null
                        ? defaults.maxSourceIps()
                        : request.maxSourceIps());
    }

    public UserPolicyResponse updatePolicy(UUID nodeId,
                                           String userId,
                                           UpdateUserPolicyRequest request,
                                           UUID actorUserId) {
        ManagedNode node = nodeService.getNode(nodeId);
        UserPolicyResponse response = client.updateUserPolicy(node, userId, request);
        if (response != null && response.success()) {
            invalidateUserSnapshots(nodeId);
            syncAllocationPolicy(nodeId, userId, response);
        }
        audit("USER_POLICY_UPDATED", actorUserId, nodeId, userId, "更新节点用户限制策略");
        return response;
    }

    public UserPolicyMigrationResponse migrateAllUsersToDefaultPolicy(UUID nodeId, UUID actorUserId) {
        ManagedNode node = nodeService.getNode(nodeId);
        UserPolicyDefaultsService.DefaultUserPolicy defaults = defaultUserPolicy();
        UpdateUserPolicyRequest request = new UpdateUserPolicyRequest(
                defaults.trafficLimitBytes(), defaults.maxSourceIps());
        List<UserSummary> users = scanAllUsers(node, null);
        List<UserPolicyMigrationFailure> failures = new ArrayList<>();
        int succeeded = 0;
        for (UserSummary user : users) {
            try {
                UserPolicyResponse response = client.updateUserPolicy(node, user.userId(), request);
                if (response == null || !response.success()) {
                    failures.add(new UserPolicyMigrationFailure(user.userId(), "节点未确认策略更新成功"));
                    continue;
                }
                syncAllocationPolicy(nodeId, user.userId(), response);
                succeeded++;
            } catch (RuntimeException exception) {
                failures.add(new UserPolicyMigrationFailure(
                        user.userId(), sanitizeMigrationError(exception.getMessage())));
            }
        }
        invalidateUserSnapshots(nodeId);
        audit("USER_POLICY_DEFAULTS_MIGRATED", actorUserId, nodeId, null,
                "批量更新用户限制策略：成功 " + succeeded + "，失败 " + failures.size());
        return new UserPolicyMigrationResponse(
                nodeId, node.getName(), users.size(), succeeded, failures.size(),
                defaults.trafficLimitBytes(), defaults.maxSourceIps(), List.copyOf(failures));
    }

    private UserPolicyDefaultsService.DefaultUserPolicy defaultUserPolicy() {
        return policyDefaultsService == null
                ? new UserPolicyDefaultsService.DefaultUserPolicy(
                        ControlPlaneSettings.INITIAL_TRAFFIC_LIMIT_BYTES,
                        ControlPlaneSettings.INITIAL_MAX_SOURCE_IPS)
                : policyDefaultsService.getDefaults();
    }

    private void syncAllocationPolicy(UUID nodeId, String userId, UserPolicyResponse response) {
        allocationRepository.findAllByNodeIdAndControlUserIdAndStateIn(
                        nodeId, userId, ACTIVE_ALLOCATION_STATES)
                .forEach(allocation -> {
                    allocation.setUserPolicy(response.trafficLimitBytes(), response.maxSourceIps());
                    allocationRepository.save(allocation);
                });
    }

    private String sanitizeMigrationError(String message) {
        if (message == null || message.isBlank()) {
            return "节点策略更新失败";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    public UserConnection getConnections(UUID nodeId, String userId) {
        ManagedNode node = nodeService.getNode(nodeId);
        UserConnection connection = client.getConnections(node, userId);
        return repairConnectionCountry(node, userId, connection, latestAllocation(nodeId, userId));
    }

    public List<BatchConnectionResult> getConnectionsBatch(UUID nodeId, List<String> requestedUserIds) {
        if (requestedUserIds == null || requestedUserIds.isEmpty()) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        if (requestedUserIds.size() > 100) {
            throw new IllegalArgumentException("单次最多读取 100 个用户连接");
        }
        LinkedHashSet<String> uniqueUserIds = new LinkedHashSet<>();
        for (String userId : requestedUserIds) {
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("用户 ID 不能为空");
            }
            uniqueUserIds.add(userId.trim());
        }

        ManagedNode node = nodeService.getNode(nodeId);
        Map<String, ResidentialAllocation> allocationByUser = new LinkedHashMap<>();
        List<ResidentialAllocation> storedAllocations = allocationRepository
                .findAllByNodeIdAndControlUserIdInAndStateIn(nodeId, uniqueUserIds, List.of("ACTIVE"));
        if (storedAllocations != null) {
            storedAllocations.forEach(allocation -> allocationByUser.merge(
                    allocation.getControlUserId(), allocation,
                    (current, candidate) -> newer(current, candidate)));
        }

        Map<String, BatchConnectionResult> resultByUser = new LinkedHashMap<>();
        List<String> remoteUserIds = new ArrayList<>();
        for (String userId : uniqueUserIds) {
            ResidentialAllocation allocation = allocationByUser.get(userId);
            if (allocation == null) {
                remoteUserIds.add(userId);
                continue;
            }
            try {
                UserConnection connection = provisioningService.storedConnection(allocation);
                if (connection != null && !needsCountryRepair(connection)) {
                    resultByUser.put(userId, new BatchConnectionResult(userId, connection, null));
                } else {
                    remoteUserIds.add(userId);
                }
            } catch (RuntimeException ignored) {
                remoteUserIds.add(userId);
            }
        }

        if (!remoteUserIds.isEmpty()) {
            int concurrency = Math.min(6, remoteUserIds.size());
            try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
                Map<String, Future<BatchConnectionResult>> futures = new LinkedHashMap<>();
                remoteUserIds.forEach(userId -> futures.put(userId, executor.submit(() -> {
                    try {
                        UserConnection connection = client.getConnections(node, userId);
                        connection = repairConnectionCountry(
                                node, userId, connection, allocationByUser.get(userId));
                        return new BatchConnectionResult(userId, connection, null);
                    } catch (RuntimeException exception) {
                        String message = exception.getMessage();
                        return new BatchConnectionResult(
                                userId, null,
                                message == null || message.isBlank() ? "读取连接信息失败" : message);
                    }
                })));
                futures.forEach((userId, future) -> {
                    try {
                        resultByUser.put(userId, future.get());
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        resultByUser.put(userId, new BatchConnectionResult(userId, null, "读取连接信息失败"));
                    } catch (ExecutionException exception) {
                        resultByUser.put(userId, new BatchConnectionResult(userId, null, "读取连接信息失败"));
                    }
                });
            }
        }

        return uniqueUserIds.stream().map(resultByUser::get).toList();
    }

    private ResidentialAllocation latestAllocation(UUID nodeId, String userId) {
        List<ResidentialAllocation> allocations = allocationRepository
                .findAllByNodeIdAndControlUserIdAndStateIn(nodeId, userId, ACTIVE_ALLOCATION_STATES);
        if (allocations == null || allocations.isEmpty()) {
            return null;
        }
        return allocations.stream().reduce(this::newer).orElse(null);
    }

    private UserConnection repairConnectionCountry(ManagedNode node,
                                                   String userId,
                                                   UserConnection connection,
                                                   ResidentialAllocation allocation) {
        if (!needsCountryRepair(connection) || ipCountryResolver == null) {
            return connection;
        }
        String sourceIp = firstText(
                asText(connection.protocolInfo().get("sourceIp")),
                allocation == null ? null : allocation.getProxySourceIp());
        if (sourceIp == null) {
            return connection;
        }
        try {
            CountryInfo country = ipCountryResolver.resolve(sourceIp);
            if (!isKnownCountryCode(country == null ? null : country.code())) {
                return connection;
            }
            UserConnection enriched = enrichConnectionCountry(connection, sourceIp, country);
            try {
                OperationResponse updated = client.updateProxyMetadata(
                        node,
                        userId,
                        new ProxyMetadataUpdateRequest(
                                sourceIp, null, null,
                                country.code().toUpperCase(Locale.ROOT), country.name(), country.city()));
                if (updated != null && updated.success()) {
                    UserConnection refreshed = client.getConnections(node, userId);
                    return needsCountryRepair(refreshed)
                            ? enrichConnectionCountry(refreshed, sourceIp, country)
                            : refreshed;
                }
            } catch (RuntimeException ignored) {
                // The current response can still use the resolved country when persistence is unavailable.
            }
            return enriched;
        } catch (RuntimeException ignored) {
            // GeoIP enrichment and metadata backfill must not break connection viewing.
            return connection;
        }
    }

    private UserConnection enrichConnectionCountry(UserConnection connection,
                                                     String sourceIp,
                                                     CountryInfo country) {
        Map<String, Object> protocolInfo = new LinkedHashMap<>(connection.protocolInfo());
        protocolInfo.put("sourceIp", sourceIp);
        protocolInfo.put("ip", firstText(asText(protocolInfo.get("ip")), sourceIp));
        protocolInfo.put("countryCode", country.code().toUpperCase(Locale.ROOT));
        protocolInfo.put("countryName", country.name());
        if (country.city() != null && !country.city().isBlank()) {
            protocolInfo.put("cityName", country.city());
        }
        return new UserConnection(
                connection.success(), connection.userId(), connection.uuid(), connection.protocols(),
                connection.vless(), connection.vmess(), connection.socks(), connection.proxyBound(),
                connection.createdAt(), connection.protocolsAll(), protocolInfo);
    }

    private boolean needsCountryRepair(UserConnection connection) {
        if (connection == null || !connection.proxyBound()) {
            return false;
        }
        return !isKnownCountryCode(asText(connection.protocolInfo().get("countryCode")));
    }

    private boolean isKnownCountryCode(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("^[A-Z]{2}$")
                && !"XX".equals(normalized)
                && !"ZZ".equals(normalized);
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    public TrafficResponse getTraffic(UUID nodeId, String userId) {
        return client.getTraffic(nodeService.getNode(nodeId), userId);
    }

    public ProxyDetails getProxy(UUID nodeId, String userId) {
        return client.getProxy(nodeService.getNode(nodeId), userId);
    }

    public OperationResponse bindProxy(UUID nodeId, BindProxyRequest request, String idempotencyKey) {
        return bindProxy(nodeId, request, idempotencyKey, null);
    }

    public OperationResponse bindProxy(UUID nodeId, BindProxyRequest request, String idempotencyKey, UUID actorUserId) {
        ManagedNode node = nodeService.getNode(nodeId);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey.trim();
        OperationResponse response = operationService.execute(node, key, "BIND_PROXY", request, OperationResponse.class,
                () -> client.bindProxy(node, request, key));
        if (response != null && response.success()) {
            invalidateUserSnapshots(nodeId);
        }
        audit("PROXY_BOUND", actorUserId, nodeId, request.userId(), "绑定节点用户出口代理");
        return response;
    }

    public OperationResponse deleteUser(UUID nodeId, String userId, String idempotencyKey) {
        return deleteUser(nodeId, userId, idempotencyKey, null);
    }

    public OperationResponse deleteUser(UUID nodeId, String userId, String idempotencyKey, UUID actorUserId) {
        ManagedNode node = nodeService.getNode(nodeId);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey.trim();
        try {
            OperationResponse response = operationService.execute(
                    node,
                    key,
                    "DELETE_USER",
                    Map.of("userId", userId),
                    OperationResponse.class,
                    () -> client.deleteUser(node, userId, key));
            if (response != null) {
                if (response.success()) {
                    releaseLocalAllocations(node, userId);
                    invalidateUserSnapshots(nodeId);
                    audit("USER_DELETED", actorUserId, nodeId, userId, "删除节点用户");
                } else if (isRemoteUserMissing(200, response.message())) {
                    // Some older Node Manager versions return HTTP 200 with
                    // success=false when the user was already deleted. Treat
                    // that as an idempotent delete and release stale local
                    // allocation records.
                    releaseLocalAllocations(node, userId);
                    invalidateUserSnapshots(nodeId);
                    audit("USER_DELETED", actorUserId, nodeId, userId, "删除节点用户并释放本地分配");
                    return new OperationResponse(true, userId,
                            "远端节点用户已不存在，本地分配记录已释放");
                }
            }
            return response;
        } catch (RemoteNodeException exception) {
            // DELETE is intentionally idempotent: if the remote user was
            // already removed, the desired state has been reached. Release
            // only for an explicit missing-user response; a timeout, 5xx, or
            // unrelated conflict must remain visible to the caller.
            if (isRemoteUserMissing(exception)) {
                releaseLocalAllocations(node, userId);
                invalidateUserSnapshots(nodeId);
                audit("USER_DELETED", actorUserId, nodeId, userId, "删除节点用户并释放本地分配");
                return new OperationResponse(true, userId, "远端节点用户已不存在，本地分配记录已释放");
            }
            throw exception;
        }
    }

    private boolean isRemoteUserMissing(RemoteNodeException exception) {
        return isRemoteUserMissing(exception.getStatusCode(), exception.getMessage());
    }

    private boolean isRemoteUserMissing(int statusCode, String message) {
        if (statusCode == 404) {
            return true;
        }
        if ((statusCode != 409 && statusCode != 200) || message == null) {
            return false;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("user not found")
                || normalized.contains("user does not exist")
                || normalized.contains("no such user")
                || normalized.contains("user already deleted")
                || normalized.contains("\u7528\u6237\u4e0d\u5b58\u5728")
                || normalized.contains("\u7528\u6237\u672a\u627e\u5230");
    }

    private void releaseLocalAllocations(ManagedNode node, String userId) {
        List<ResidentialAllocation> allocations = allocationRepository
                .findAllByControlUserIdAndStateIn(userId, ACTIVE_ALLOCATION_STATES)
                .stream()
                .filter(allocation -> provisioningService.sharesServer(node, allocation.getNode()))
                .toList();
        allocations.forEach(allocation ->
                allocation.fail("远端节点用户已删除，已释放本地分配记录", true));
        if (!allocations.isEmpty()) {
            allocationRepository.saveAll(allocations);
        }
    }

    public ReloadResponse reload(UUID nodeId) {
        ManagedNode node = nodeService.getNode(nodeId);
        ReloadResponse response = client.reload(node);
        nodeService.refresh(nodeId);
        return response;
    }

    private void audit(String eventType, UUID actorUserId, UUID nodeId, String userId, String summary) {
        if (auditLogService != null) {
            auditLogService.record(eventType, actorUserId, "NODE_USER", nodeId + "/" + userId, summary);
        }
    }
}
