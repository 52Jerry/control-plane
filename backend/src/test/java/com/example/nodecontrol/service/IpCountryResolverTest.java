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
                        "{\"country\":\"United States\",\"country_code\":\"US\",\"city\":\"Los Angeles\",\"ip\":\"203.0.113.10\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(resolver.resolve("203.0.113.10"))
                .isEqualTo(new IpCountryResolver.CountryInfo("美国", "US", "Los Angeles"));
        assertThat(resolver.resolve("203.0.113.10"))
                .isEqualTo(new IpCountryResolver.CountryInfo("美国", "US", "Los Angeles"));
        server.verify();
    }

    @Test
    void fallsBackWhenGeoJsIsUnavailable() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://get.geojs.io/v1/ip/geo");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IpCountryResolver resolver = new IpCountryResolver(builder.build(), properties());

        server.expect(requestTo("https://get.geojs.io/v1/ip/geo/198.51.100.10.json"))
                .andRespond(withServerError());
        server.expect(requestTo("https://ipwho.is/198.51.100.10"))
                .andRespond(withSuccess(
                        "{\"success\":true,\"country\":\"Japan\",\"country_code\":\"JP\",\"city\":\"Tokyo\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(resolver.resolve("198.51.100.10"))
                .isEqualTo(new IpCountryResolver.CountryInfo("日本", "JP", "Tokyo"));
        server.verify();
    }

    @Test
    void usesSecondaryFallbackWhenPrimaryProvidersAreUnavailable() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://get.geojs.io/v1/ip/geo");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IpCountryResolver resolver = new IpCountryResolver(builder.build(), properties());

        server.expect(requestTo("https://get.geojs.io/v1/ip/geo/207.152.99.183.json"))
                .andRespond(withServerError());
        server.expect(requestTo("https://ipwho.is/207.152.99.183"))
                .andRespond(withServerError());
        server.expect(requestTo("http://ip-api.com/json/207.152.99.183?fields=status,country,countryCode,city"))
                .andRespond(withSuccess(
                        "{\"status\":\"success\",\"country\":\"United States\",\"countryCode\":\"US\",\"city\":\"Los Angeles\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(resolver.resolve("207.152.99.183"))
                .isEqualTo(new IpCountryResolver.CountryInfo("美国", "US", "Los Angeles"));
        server.verify();
    }

    @Test
    void doesNotKeepUnknownWhenFailureCachingIsDisabled() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://get.geojs.io/v1/ip/geo");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ControlPlaneProperties properties = properties();
        properties.getGeoIp().setFallbackBaseUrl("");
        properties.getGeoIp().setSecondaryFallbackBaseUrl("");
        properties.getGeoIp().setFailureCacheTtlSeconds(0);
        IpCountryResolver resolver = new IpCountryResolver(builder.build(), properties);

        server.expect(requestTo("https://get.geojs.io/v1/ip/geo/198.51.100.20.json"))
                .andRespond(withServerError());
        server.expect(requestTo("https://get.geojs.io/v1/ip/geo/198.51.100.20.json"))
                .andRespond(withSuccess(
                        "{\"country\":\"Singapore\",\"country_code\":\"SG\",\"city\":\"Singapore\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(resolver.resolve("198.51.100.20")).isEqualTo(IpCountryResolver.UNKNOWN);
        assertThat(resolver.resolve("198.51.100.20"))
                .isEqualTo(new IpCountryResolver.CountryInfo("新加坡", "SG", "Singapore"));
        server.verify();
    }

    private ControlPlaneProperties properties() {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.getGeoIp().setBaseUrl("https://get.geojs.io/v1/ip/geo");
        properties.getGeoIp().setConnectTimeoutMillis(500);
        properties.getGeoIp().setReadTimeoutMillis(500);
        properties.getGeoIp().setFallbackBaseUrl("https://ipwho.is");
        properties.getGeoIp().setSecondaryFallbackBaseUrl("http://ip-api.com/json");
        return properties;
    }
}
