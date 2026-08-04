package com.example.nodecontrol.service;

import com.example.nodecontrol.client.NodeManagerClient;
import com.example.nodecontrol.client.RemoteNodeException;
import com.example.nodecontrol.config.ControlPlaneProperties;
import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.ManagedNodeRepository;
import com.example.nodecontrol.domain.ResidentialAllocation;
import com.example.nodecontrol.domain.ResidentialAllocationRepository;
import com.example.nodecontrol.dto.ControlPlaneModels.AllocationView;
import com.example.nodecontrol.dto.ControlPlaneModels.ProvisionRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionBatchResponse;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionRequest;
import com.example.nodecontrol.dto.ControlPlaneModels.ProxyProvisionResult;
import com.example.nodecontrol.dto.RemoteModels.CreateUserRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.ProxyConfig;
import com.example.nodecontrol.dto.RemoteModels.SocksConnection;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.security.SecretCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Service
public class ProvisioningService {

    private static final Collection<String> CAPACITY_STATES = List.of("PROVISIONING", "RETRYABLE", "ACTIVE");
    private static final List<String> RESIDENTIAL_PROTOCOLS = List.of("vless", "vmess", "socks");

    private final ResidentialAllocationRepository allocationRepository;
    private final ManagedNodeRepository nodeRepository;
    private final NodeManagerClient client;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final ControlPlaneProperties properties;
    private final TransactionTemplate transactionTemplate;

    public ProvisioningService(ResidentialAllocationRepository allocationRepository,
                               ManagedNodeRepository nodeRepository,
                               NodeManagerClient client,
                               SecretCipher secretCipher,
                               ObjectMapper objectMapper,
                               ControlPlaneProperties properties,
                               PlatformTransactionManager transactionManager) {
        this.allocationRepository = allocationRepository;
        this.nodeRepository = nodeRepository;
        this.client = client;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public AllocationView provision(String idempotencyKey, ProvisionRequest request) {
        String requestKey = normalizeRequestKey(idempotencyKey);
        validateProtocols(request.protocols());
        ProvisionRequest effectiveRequest = withEffectiveUserId(request, requestKey);
        String requestHash = hash(effectiveRequest);
        ResidentialAllocation allocation = createOrLoad(requestKey, requestHash, effectiveRequest);
        return executeProvisioning(allocation, effectiveRequest, null);
    }

    public ProxyProvisionBatchResponse provisionProxyBatch(String idempotencyKey,
                                                           ProxyProvisionRequest request) {
        String batchKey = normalizeRequestKey(idempotencyKey);
        List<ProxyRowParseResult> parsedRows = parseProxyRows(request.input());
        List<PreparedProxyRow> preparedRows = new ArrayList<>();
        List<ProxyProvisionResult> results = new ArrayList<>();
        for (ProxyRowParseResult parsed : parsedRows) {
            if (parsed.error() != null) {
                results.add(new ProxyProvisionResult(
                        parsed.rowNumber(), parsed.sourceIp(), parsed.sourceDomain(),
                        parsed.sourceAddress(), parsed.sourcePort(), null, parsed.error()));
                continue;
            }
            ParsedProxyRow row = parsed.row();
            try {
                String userId = normalizeBatchUserId(row.username(), batchKey, row.rowNumber());
                String rowKey = rowRequestKey(batchKey, row.rowNumber());
                ProxyConfig proxy = new ProxyConfig(
                        "socks5", row.server(), row.port(), row.username(), row.password());
                ProvisionRequest provisionRequest = new ProvisionRequest(
                        userId, RESIDENTIAL_PROTOCOLS, request.preferredNodeId());
                ProxyRequestHash hashInput = new ProxyRequestHash(
                        provisionRequest, row.sourceIp(), row.sourceAddress(), proxy);
                ResidentialAllocation allocation = createOrLoadProxy(
                        rowKey, hash(hashInput), provisionRequest, row, proxy);
                preparedRows.add(new PreparedProxyRow(row, provisionRequest, proxy, allocation));
            } catch (RuntimeException exception) {
                results.add(new ProxyProvisionResult(
                        row.rowNumber(), row.sourceIp(), row.sourceAddress(), row.server(), row.port(), null,
                        sanitizeError(exception.getMessage(), null)));
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
        return new ProxyProvisionBatchResponse(
                results.size(), succeeded, results.size() - succeeded, results);
    }

    public AllocationView retry(UUID allocationId) {
        ResidentialAllocation allocation = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new NoSuchElementException("分配记录不存在"));
        if ("ACTIVE".equals(allocation.getState())) {
            return toView(allocation);
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
        return executeProvisioning(allocation, request, proxyFrom(allocation));
    }

    private AllocationView executeProvisioning(ResidentialAllocation allocation,
                                                ProvisionRequest request,
                                                ProxyConfig proxy) {
        PreparedProvisioning prepared = prepare(allocation.getId(), request);
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
                            existing.vless(), existing.vmess(), existing.socks(), existing.proxyBound());
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
            allocationRepository.findByControlUserId(request.userId()).ifPresent(conflict -> {
                throw new IllegalStateException("用户 ID 已存在于其他分配记录");
            });
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
            allocationRepository.findByControlUserId(request.userId()).ifPresent(conflict -> {
                throw new IllegalStateException("批量生成的用户 ID 已存在于其他分配记录");
            });
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

    private PreparedProvisioning prepare(UUID allocationId, ProvisionRequest request) {
        PreparedProvisioning prepared = transactionTemplate.execute(
                status -> prepareLocked(allocationId, request));
        if (prepared == null) {
            throw new IllegalStateException("准备节点开通任务失败");
        }
        return prepared;
    }

    private PreparedProvisioning prepareLocked(UUID allocationId,
                                                ProvisionRequest request) {
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
            node = selectNode(request.preferredNodeId());
        } else if (!isAllocatable(node)) {
            throw new IllegalStateException("该分配首次选中的节点当前不可用，请恢复节点后重试");
        }
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

    private ManagedNode selectNode(UUID preferredNodeId) {
        List<ManagedNode> nodes = nodeRepository.findAllocatableNodesForUpdate();
        if (preferredNodeId != null) {
            nodes = nodes.stream().filter(node -> node.getId().equals(preferredNodeId)).toList();
            if (nodes.isEmpty()) {
                throw new IllegalStateException("指定节点当前不可用于自动开通");
            }
        }
        return nodes.stream()
                .filter(this::hasCapacity)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有在线且有剩余容量的节点管理器"));
    }

    private boolean hasCapacity(ManagedNode node) {
        long managed = allocationRepository.countByNodeIdAndStateIn(node.getId(), CAPACITY_STATES);
        return Math.max(node.getUserCount(), managed) < node.getMaxUsers();
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
            allocation.complete(
                    response,
                    secretCipher.encrypt(response.vless()),
                    secretCipher.encrypt(response.vmess()),
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
            connection = new UserConnection(
                    true,
                    allocation.getRemoteUserId(),
                    allocation.getConnectionUuid(),
                    splitProtocols(allocation.getProtocols()),
                    secretCipher.decrypt(allocation.getVlessCipher()),
                    secretCipher.decrypt(allocation.getVmessCipher()),
                    socks,
                    allocation.isProxyBound(),
                    allocation.getCompletedAt());
        }
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
                allocation.getProxySourceDomain(),
                allocation.getProxySourcePort());
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
            String[] columns = line.split("\\s+");
            String sourceIp = candidateColumn(columns, columns.length == 6 ? 1 : 0);
            String sourceDomain = normalizeSourceAddressCandidate(
                    candidateColumn(columns, columns.length == 6 ? 2 : 1));
            String sourceAddress = sourceDomain == null ? sourceIp : sourceDomain;
            Integer sourcePort = findPort(columns);
            try {
                ParsedProxyRow row = switch (columns.length) {
                    case 5 -> parsedRow(rowNumber, columns[0], columns[1], columns[2], columns[3], columns[4]);
                    case 6 -> parsedRow(rowNumber, columns[1], columns[2], columns[3], columns[4], columns[5]);
                    default -> throw new IllegalArgumentException(
                            "第 " + rowNumber + " 行格式错误，应为 5 列或带序号的 6 列");
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

    private ParsedProxyRow parsedRow(int rowNumber,
                                     String sourceIp,
                                     String sourceDomain,
                                     String portValue,
                                     String username,
                                     String password) {
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
                normalizedUsername, normalizedPassword);
    }

    private String candidateColumn(String[] columns, int index) {
        return index >= 0 && index < columns.length ? columns[index] : null;
    }

    private String normalizeSourceAddressCandidate(String value) {
        return value == null || value.isBlank() || "-".equals(value) ? null : value;
    }

    private Integer findPort(String[] columns) {
        int index = columns.length == 6 ? 3 : 2;
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
                secretCipher.decrypt(allocation.getProxyPasswordCipher()));
    }

    private ProxyProvisionResult toProxyResult(ParsedProxyRow row,
                                               AllocationView allocation,
                                               String error) {
        return new ProxyProvisionResult(
                row.rowNumber(), row.sourceIp(), row.sourceAddress(),
                row.server(), row.port(), allocation, error);
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
        if (view == null || (view.proxyUsername() == null && view.proxyPassword() == null)) {
            return view;
        }
        return new AllocationView(
                view.id(),
                view.requestKey(),
                view.userId(),
                view.protocols(),
                view.state(),
                view.nodeId(),
                view.nodeName(),
                view.nodeHost(),
                view.connection(),
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
                && hasUsableSocksConnection(connection.socks());
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
            String password
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
