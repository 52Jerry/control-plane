package com.example.nodecontrol.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 节点管理器 HTTP 客户端超时配置。
 * 未设置超时时，对离线节点的请求会无限挂起，长时间占用数据库连接
 * （连接池泄漏检测曾报 60 秒以上）。IpCountryResolver 使用自己的
 * GeoIP 超时工厂，不受此配置影响。
 */
@Configuration
public class RestClientTimeoutConfig {

    @Bean
    RestClientCustomizer nodeManagerTimeoutCustomizer() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return builder -> builder.requestFactory(factory);
    }
}
