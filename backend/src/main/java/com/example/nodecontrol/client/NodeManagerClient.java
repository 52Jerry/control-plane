package com.example.nodecontrol.client;

import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.dto.RemoteModels.AgentHeartbeat;
import com.example.nodecontrol.dto.RemoteModels.AgentInfo;
import com.example.nodecontrol.dto.RemoteModels.BindProxyRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserRequest;
import com.example.nodecontrol.dto.RemoteModels.CreateUserResponse;
import com.example.nodecontrol.dto.RemoteModels.OperationResponse;
import com.example.nodecontrol.dto.RemoteModels.ReloadResponse;
import com.example.nodecontrol.dto.RemoteModels.TrafficResponse;
import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.UserPage;
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
import java.util.UUID;

@Component
public class NodeManagerClient {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    public NodeManagerClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
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

    public CreateUserResponse createUser(ManagedNode node, CreateUserRequest request) {
        return execute(() -> client(node).post()
                .uri("/api/user/create")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(CreateUserResponse.class));
    }

    public UserConnection getConnections(ManagedNode node, String userId) {
        return execute(() -> client(node).get()
                .uri("/api/user/{userId}/connections", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(UserConnection.class));
    }

    public TrafficResponse getTraffic(ManagedNode node, String userId) {
        return execute(() -> client(node).get()
                .uri("/api/user/{userId}/traffic", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(TrafficResponse.class));
    }

    public OperationResponse bindProxy(ManagedNode node, BindProxyRequest request) {
        return execute(() -> client(node).post()
                .uri("/api/user/bind-proxy")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(OperationResponse.class));
    }

    public OperationResponse deleteUser(ManagedNode node, String userId) {
        return execute(() -> client(node).delete()
                .uri("/api/user/delete/{userId}", userId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(OperationResponse.class));
    }

    public ReloadResponse reload(ManagedNode node) {
        return execute(() -> client(node).post()
                .uri("/api/singbox/reload")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(ReloadResponse.class));
    }

    private RestClient client(ManagedNode node) {
        return client(node.getBaseUrl(), node.getApiToken());
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
                JsonNode detail = body.path("detail");
                if (detail.isTextual()) {
                    message = detail.asText();
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

    @FunctionalInterface
    private interface ClientCall<T> {
        T execute();
    }
}

