package com.example.nodecontrol.client;

import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.dto.RemoteModels.AgentHeartbeat;
import com.example.nodecontrol.dto.RemoteModels.AgentInfo;
import com.example.nodecontrol.dto.RemoteModels.BindProxyRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.OperationResponse;
import com.example.nodecontrol.dto.RemoteModels.ProxyDetails;
import com.example.nodecontrol.dto.RemoteModels.ReloadResponse;
import com.example.nodecontrol.dto.RemoteModels.TrafficResponse;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.UserPage;
import com.example.nodecontrol.security.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
@Component
public class NodeManagerClient {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;

    public NodeManagerClient(RestClient.Builder restClientBuilder,
                             ObjectMapper objectMapper,
                             SecretCipher secretCipher) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
    }

    public AgentInfo getAgentInfo(String baseUrl, String token) {
        return execute(() -> client(baseUrl, token).get()
                .uri("/api/agent/info")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(AgentInfo.class));
    }

    public AgentHeartbeat getHeartbeat(ManagedNode node) {
        return execute(() -> client(node).get()
                .uri("/api/agent/heartbeat")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(AgentHeartbeat.class));
    }

    public UserPage getUsers(ManagedNode node, int page, int pageSize, String keyword) {
        return execute(() -> client(node).get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/users")
                        .queryParam("page", page)
                        .queryParam("pageSize", pageSize)
                        .queryParamIfPresent("keyword", java.util.Optional.ofNullable(keyword).filter(value -> !value.isBlank()))
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(UserPage.class));
    }

    public CreateUserResponse createUser(ManagedNode node, CreateUserRequest request, String idempotencyKey) {
        return execute(() -> client(node).post()
                .uri("/api/user/create")
                .header("Idempotency-Key", idempotencyKey)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(CreateUserResponse.class));
    }

    public UserConnection getConnections(ManagedNode node, String userId) {
        return execute(() -> {
            JsonNode body = client(node).get()
                    .uri("/api/user/{userId}/connections", userId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleError)
                    .body(JsonNode.class);
            if (body == null || body.isNull()) {
                throw new RemoteNodeException(502, "节点返回了空响应");
            }

            // Some older Node Manager versions returned HTTP 200 with a
            // success=false envelope instead of using HTTP 409/404 when the
            // requested user had already been deleted. Normalize that shape
            // to the same exception used by the status-code path so stale
            // local allocations can be released safely.
            if (hasExplicitFailure(body)) {
                String message = extractErrorMessage(body);
                int status = message == null || message.isBlank() ? 502 : 409;
                throw new RemoteNodeException(status,
                        message == null || message.isBlank() ? "节点用户查询失败" : message);
            }
            try {
                return objectMapper.treeToValue(unwrapEnvelope(body), UserConnection.class);
            } catch (IOException exception) {
                throw new RemoteNodeException(502, "节点返回的用户连接响应格式无效", exception);
            }
        });
    }

    public TrafficResponse getTraffic(ManagedNode node, String userId) {
        return execute(() -> client(node).get()
                .uri("/api/user/{userId}/traffic", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(TrafficResponse.class));
    }

    public ProxyDetails getProxy(ManagedNode node, String userId) {
        return execute(() -> client(node).get()
                .uri("/api/user/{userId}/proxy", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(ProxyDetails.class));
    }

    public OperationResponse bindProxy(ManagedNode node, BindProxyRequest request, String idempotencyKey) {
        return execute(() -> client(node).post()
                .uri("/api/user/bind-proxy")
                .header("Idempotency-Key", idempotencyKey)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(OperationResponse.class));
    }

    public OperationResponse deleteUser(ManagedNode node, String userId, String idempotencyKey) {
        return execute(() -> {
            JsonNode body = client(node).delete()
                    .uri("/api/user/delete/{userId}", userId)
                    .header("Idempotency-Key", idempotencyKey)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleError)
                    .body(JsonNode.class);
            if (body == null || body.isNull()) {
                throw new RemoteNodeException(502, "节点返回了空响应");
            }
            try {
                OperationResponse response = objectMapper.treeToValue(unwrapEnvelope(body), OperationResponse.class);
                // Older Node Manager versions used HTTP 200 with
                // {success:false,message:"user not found"} when DELETE was
                // repeated. DELETE is idempotent, so normalize that response
                // to success and let the control plane release its stale
                // local allocation.
                String message = extractErrorMessage(body);
                if (response != null && !response.success() && isMissingUserMessage(message)) {
                    return new OperationResponse(true, userId, "remote user already absent");
                }
                return response;
            } catch (IOException exception) {
                throw new RemoteNodeException(502, "节点返回的删除响应格式无效", exception);
            }
        });
    }

    public ReloadResponse reload(ManagedNode node) {
        return execute(() -> client(node).post()
                .uri("/api/singbox/reload")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(ReloadResponse.class));
    }

    private RestClient client(ManagedNode node) {
        return client(node.getBaseUrl(), secretCipher.decrypt(node.getStoredApiToken()));
    }

    private RestClient client(String baseUrl, String token) {
        return restClientBuilder.clone()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    private <T> T execute(ClientCall<T> call) {
        try {
            T result = call.execute();
            if (result == null) {
                throw new RemoteNodeException(502, "节点返回了空响应");
            }
            return result;
        } catch (RemoteNodeException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new RemoteNodeException(502, "无法连接节点: " + exception.getMessage(), exception);
        }
    }

    private void handleError(org.springframework.http.HttpRequest request,
                             org.springframework.http.client.ClientHttpResponse response) throws IOException {
        String raw = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        String message = raw;
        if (!raw.isBlank()) {
            try {
                JsonNode body = objectMapper.readTree(raw);
                String extracted = extractErrorMessage(body);
                if (extracted != null && !extracted.isBlank()) {
                    message = extracted;
                }
            } catch (IOException ignored) {
                message = raw;
            }
        }
        if (message == null || message.isBlank()) {
            message = "节点请求失败: HTTP " + response.getStatusCode().value();
        }
        throw new RemoteNodeException(response.getStatusCode().value(), message);
    }

    private String extractErrorMessage(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        for (String field : new String[]{"detail", "message", "error"}) {
            JsonNode value = body.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isTextual()) {
                return value.asText();
            }
            if (value.isArray()) {
                for (JsonNode item : value) {
                    if (item.isTextual()) {
                        return item.asText();
                    }
                    JsonNode itemMessage = item.get("msg");
                    if (itemMessage != null && itemMessage.isTextual()) {
                        return itemMessage.asText();
                    }
                }
            }
        }
        for (String field : new String[]{"data", "result", "payload"}) {
            String nestedMessage = extractErrorMessage(body.get(field));
            if (nestedMessage != null && !nestedMessage.isBlank()) {
                return nestedMessage;
            }
        }
        return null;
    }

    private boolean hasExplicitFailure(JsonNode body) {
        if (body == null || body.isNull()) {
            return false;
        }
        JsonNode success = body.get("success");
        if (success != null && success.isBoolean() && !success.asBoolean()) {
            return true;
        }
        for (String field : new String[]{"data", "result", "payload"}) {
            if (hasExplicitFailure(body.get(field))) {
                return true;
            }
        }
        return false;
    }

    private JsonNode unwrapEnvelope(JsonNode body) {
        if (body == null || body.isNull()) {
            return body;
        }
        for (String field : new String[]{"data", "result", "payload"}) {
            JsonNode nested = body.get(field);
            if (nested != null && nested.isObject()
                    && (nested.has("success") || nested.has("userId") || nested.has("uuid")
                    || nested.has("protocols") || nested.has("vless") || nested.has("vmess"))) {
                return nested;
            }
        }
        return body;
    }

    private boolean isMissingUserMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("user not found")
                || normalized.contains("user does not exist")
                || normalized.contains("no such user")
                || normalized.contains("user already deleted")
                || normalized.contains("\u7528\u6237\u4e0d\u5b58\u5728")
                || normalized.contains("\u7528\u6237\u672a\u627e\u5230");
    }

    @FunctionalInterface
    private interface ClientCall<T> {
        T execute();
    }
}

