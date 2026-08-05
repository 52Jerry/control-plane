package com.example.nodecontrol.client;

import com.example.nodecontrol.dto.RemoteModels.UserConnection;
import com.example.nodecontrol.dto.RemoteModels.OperationResponse;
import com.example.nodecontrol.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NodeManagerClientTest {

    @Test
    void treatsLegacySuccessFalseUserNotFoundEnvelopeAsMissingUser() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NodeManagerClient client = new NodeManagerClient(
                builder,
                new ObjectMapper(),
                cipher("api-token"));

        server.expect(requestTo("http://node.example:8088/api/user/deleted-user/connections"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"success\":false,\"message\":\"user not found: deleted-user\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getConnections(node("api-token"), "deleted-user"))
                .isInstanceOf(RemoteNodeException.class)
                .satisfies(error -> {
                    RemoteNodeException exception = (RemoteNodeException) error;
                    assertThat(exception.getStatusCode()).isEqualTo(409);
                    assertThat(exception).hasMessageContaining("user not found");
                });
        server.verify();
    }

    @Test
    void extractsMessageFieldFromErrorStatusResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NodeManagerClient client = new NodeManagerClient(
                builder,
                new ObjectMapper(),
                cipher("api-token"));

        server.expect(requestTo("http://node.example:8088/api/user/missing/connections"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.CONFLICT)
                        .body("{\"message\":\"用户不存在\"}")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getConnections(node("api-token"), "missing"))
                .isInstanceOf(RemoteNodeException.class)
                .hasMessageContaining("用户不存在");
        server.verify();
    }

    @Test
    void normalizesHttp200SuccessFalseDeleteForMissingUser() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NodeManagerClient client = new NodeManagerClient(
                builder,
                new ObjectMapper(),
                cipher("api-token"));

        server.expect(requestTo("http://node.example:8088/api/user/delete/deleted-user"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess(
                        "{\"success\":false,\"userId\":\"deleted-user\",\"message\":\"user not found\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        OperationResponse response = client.deleteUser(node("api-token"), "deleted-user", "delete-key");

        assertThat(response.success()).isTrue();
        assertThat(response.userId()).isEqualTo("deleted-user");
        server.verify();
    }

    @Test
    void unwrapsNestedConnectionResponseEnvelope() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NodeManagerClient client = new NodeManagerClient(
                builder,
                new ObjectMapper(),
                cipher("api-token"));

        server.expect(requestTo("http://node.example:8088/api/user/alice/connections"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"result\":{\"success\":true,\"userId\":\"alice\",\"uuid\":\"uuid-1\","
                                + "\"protocols\":[\"socks\"],\"socks\":{\"host\":\"node.example\","
                                + "\"port\":1080,\"username\":\"alice\",\"password\":\"remote-secret\"},"
                                + "\"proxyBound\":false}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        UserConnection response = client.getConnections(node("api-token"), "alice");

        assertThat(response.success()).isTrue();
        assertThat(response.userId()).isEqualTo("alice");
        assertThat(response.uuid()).isEqualTo("uuid-1");
        assertThat(response.socks().host()).isEqualTo("node.example");
        server.verify();
    }

    @Test
    void treatsNestedSuccessFalseConnectionEnvelopeAsMissingUser() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NodeManagerClient client = new NodeManagerClient(
                builder,
                new ObjectMapper(),
                cipher("api-token"));

        server.expect(requestTo("http://node.example:8088/api/user/deleted-user/connections"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"data\":{\"success\":false,\"message\":\"user not found\"}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getConnections(node("api-token"), "deleted-user"))
                .isInstanceOf(RemoteNodeException.class)
                .satisfies(error -> {
                    RemoteNodeException exception = (RemoteNodeException) error;
                    assertThat(exception.getStatusCode()).isEqualTo(409);
                    assertThat(exception).hasMessageContaining("user not found");
                });
        server.verify();
    }

    private com.example.nodecontrol.domain.ManagedNode node(String token) {
        return new com.example.nodecontrol.domain.ManagedNode(
                "Test Node", "http://node.example:8088", token);
    }

    private SecretCipher cipher(String token) {
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.decrypt(token)).thenReturn(token);
        return cipher;
    }
}
