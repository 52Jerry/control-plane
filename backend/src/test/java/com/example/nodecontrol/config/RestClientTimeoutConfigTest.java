package com.example.nodecontrol.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientTimeoutConfigTest {

    @Test
    void configuredClientCanSendPatchRequests() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/policy", exchange -> {
            method.set(exchange.getRequestMethod());
            byte[] response = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            RestClient.Builder builder = RestClient.builder();
            new RestClientTimeoutConfig().nodeManagerTimeoutCustomizer().customize(builder);

            String response = builder.build().patch()
                    .uri("http://127.0.0.1:{port}/policy", server.getAddress().getPort())
                    .body("{\"trafficLimitBytes\":1024}")
                    .retrieve()
                    .body(String.class);

            assertThat(method).hasValue("PATCH");
            assertThat(response).isEqualTo("{\"success\":true}");
        } finally {
            server.stop(0);
        }
    }
}
