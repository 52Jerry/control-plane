package com.example.nodecontrol.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionConfigurationTest {

    @Test
    void buildsJdbcUrlFromTheSimplifiedDatabaseSettings() throws IOException {
        ConfigurableEnvironment environment = productionEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "deployment",
                java.util.Map.of("CONTROL_PLANE_DB_HOST", "db.example.com")));

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://db.example.com:3306/control-plane"
                        + "?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=true");
        assertThat(environment.getProperty("server.port")).isEqualTo("8090");
    }

    @Test
    void stillAllowsACompleteJdbcUrlOverride() throws IOException {
        ConfigurableEnvironment environment = productionEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "deployment",
                java.util.Map.of(
                        "CONTROL_PLANE_DB_HOST", "ignored.example.com",
                        "CONTROL_PLANE_DB_URL", "jdbc:mysql://custom.example.com:3307/custom")));

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://custom.example.com:3307/custom");
    }

    private ConfigurableEnvironment productionEnvironment() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "application-prod",
                new ClassPathResource("application-prod.yml"));
        sources.forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }
}
