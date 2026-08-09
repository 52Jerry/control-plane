package com.example.nodecontrol.service;

import com.example.nodecontrol.config.ControlPlaneProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IpCountryResolverTest {

    @Test
    void resolvesGeoJsCountryAndCachesTheResult() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://get.geojs.io/v1/ip/geo");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ControlPlaneProperties properties = properties();
        IpCountryResolver resolver = new IpCountryResolver(builder.build(), properties);

        server.expect(requestTo("https://get.geojs.io/v1/ip/geo/203.0.113.10.json"))
                .andRespond(withSuccess(
                        "{\"country\":\"United States\",\"country_code\":\"US\",\"ip\":\"203.0.113.10\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(resolver.resolve("203.0.113.10"))
                .isEqualTo(new IpCountryResolver.CountryInfo("美国", "US"));
        assertThat(resolver.resolve("203.0.113.10"))
                .isEqualTo(new IpCountryResolver.CountryInfo("美国", "US"));
        server.verify();
    }

    @Test
    void returnsUnknownWhenGeoJsIsUnavailable() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://get.geojs.io/v1/ip/geo");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IpCountryResolver resolver = new IpCountryResolver(builder.build(), properties());

        server.expect(requestTo("https://get.geojs.io/v1/ip/geo/198.51.100.10.json"))
                .andRespond(withServerError());

        assertThat(resolver.resolve("198.51.100.10")).isEqualTo(IpCountryResolver.UNKNOWN);
        server.verify();
    }

    private ControlPlaneProperties properties() {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.getGeoIp().setBaseUrl("https://get.geojs.io/v1/ip/geo");
        properties.getGeoIp().setConnectTimeoutMillis(500);
        properties.getGeoIp().setReadTimeoutMillis(500);
        return properties;
    }
}
