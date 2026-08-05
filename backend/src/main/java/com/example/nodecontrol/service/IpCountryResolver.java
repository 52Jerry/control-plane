package com.example.nodecontrol.service;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IpCountryResolver {

    public static final CountryInfo UNKNOWN = new CountryInfo("未知", "ZZ");

    private final RestClient restClient;
    private final ControlPlaneProperties properties;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public IpCountryResolver(RestClient.Builder restClientBuilder,
                             ControlPlaneProperties properties) {
        this(buildProductionClient(restClientBuilder, properties), properties);
    }

    IpCountryResolver(RestClient restClient,
                      ControlPlaneProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    private static RestClient buildProductionClient(RestClient.Builder restClientBuilder,
                                                    ControlPlaneProperties properties) {
        ControlPlaneProperties.GeoIp geoIp = properties.getGeoIp();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(200, geoIp.getConnectTimeoutMillis()));
        requestFactory.setReadTimeout(Math.max(200, geoIp.getReadTimeoutMillis()));
        return restClientBuilder.clone()
                .requestFactory(requestFactory)
                .baseUrl(normalizeBaseUrl(geoIp.getBaseUrl()))
                .build();
    }

    public CountryInfo resolve(String ip) {
        if (!properties.getGeoIp().isEnabled() || ip == null || ip.isBlank()) {
            return UNKNOWN;
        }
        String normalizedIp = ip.trim();
        CacheEntry cached = cache.get(normalizedIp);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.country();
        }

        CountryInfo resolved = fetch(normalizedIp);
        long ttlSeconds = Math.max(60, properties.getGeoIp().getCacheTtlSeconds());
        cache.put(normalizedIp, new CacheEntry(resolved, Instant.now().plus(Duration.ofSeconds(ttlSeconds))));
        return resolved;
    }

    private CountryInfo fetch(String ip) {
        try {
            GeoJsResponse response = restClient.get()
                    .uri("/{ip}.json", ip)
                    .retrieve()
                    .body(GeoJsResponse.class);
            if (response == null || response.countryCode() == null) {
                return UNKNOWN;
            }
            String code = response.countryCode().trim().toUpperCase(Locale.ROOT);
            if (!code.matches("^[A-Z]{2}$")) {
                return UNKNOWN;
            }
            String localizedName = Locale.of("", code).getDisplayCountry(Locale.SIMPLIFIED_CHINESE);
            if (localizedName == null || localizedName.isBlank() || localizedName.equalsIgnoreCase(code)) {
                localizedName = response.country();
            }
            if (localizedName == null || localizedName.isBlank()) {
                localizedName = "未知";
            }
            return new CountryInfo(localizedName, code);
        } catch (RestClientException | IllegalArgumentException ignored) {
            // GeoIP is optional enrichment. A lookup outage must not block node provisioning.
            return UNKNOWN;
        }
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "https://get.geojs.io/v1/ip/geo" : normalized;
    }

    public record CountryInfo(String name, String code) {
    }

    private record CacheEntry(CountryInfo country, Instant expiresAt) {
    }

    private record GeoJsResponse(
            String country,
            @JsonProperty("country_code") String countryCode
    ) {
    }
}
